package de.hofmannit.erechnung.generation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;

import de.hofmannit.erechnung.configuration.profile.ProfileDefinition;
import de.hofmannit.erechnung.mapping.InvoiceData;
import de.hofmannit.erechnung.mapping.LineItemData;
import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.model.DocumentType;

import org.mustangproject.BankDetails;
import org.mustangproject.Contact;
import org.mustangproject.Invoice;
import org.mustangproject.Item;
import org.mustangproject.Product;
import org.mustangproject.TradeParty;
import org.springframework.stereotype.Component;

/**
 * Überführt die gemappten Business Terms in das Mustang-Rechnungsmodell
 * ({@link org.mustangproject.Invoice}). Alle verwendeten Mustang-APIs wurden an
 * {@code org.mustangproject:library:2.17.0} verifiziert.
 *
 * <p>Es werden ausschließlich extrahierte bzw. konfigurierte Werte übernommen; Summen werden
 * von Mustang aus den Positionen berechnet und anschließend gegen die PDF-Summen geprüft
 * ({@link EInvoiceGenerator}).
 */
@Component
public class InvoiceModelBuilder {

    public Invoice build(InvoiceData data, ProfileDefinition profile) throws GenerationException {
        ProfileDefinition.Generation gen = profile.generation();

        TradeParty seller = new TradeParty(
                require(data, BusinessTerm.BT_27),
                require(data, BusinessTerm.BT_35),
                require(data, BusinessTerm.BT_38),
                require(data, BusinessTerm.BT_37),
                require(data, BusinessTerm.BT_40));
        data.value(BusinessTerm.BT_31).ifPresent(seller::addVATID);
        data.value(BusinessTerm.BT_32).ifPresent(seller::addTaxID);
        data.value(BusinessTerm.BT_36).ifPresent(seller::setAdditionalAddress);
        data.value(BusinessTerm.BT_34).or(() -> data.value(BusinessTerm.BT_43)).ifPresent(seller::setEmail);
        Optional<String> contactName = data.value(BusinessTerm.BT_41);
        Optional<String> contactPhone = data.value(BusinessTerm.BT_42);
        Optional<String> contactMail = data.value(BusinessTerm.BT_43);
        if (contactName.isPresent() || contactPhone.isPresent() || contactMail.isPresent()) {
            seller.setContact(new Contact(contactName.orElse(null), contactPhone.orElse(null), contactMail.orElse(null)));
        }
        data.value(BusinessTerm.BT_84).ifPresent(iban -> {
            BankDetails bank = new BankDetails(iban, data.value(BusinessTerm.BT_86).orElse(null));
            data.value(BusinessTerm.BT_85).ifPresent(bank::setAccountName);
            seller.addBankDetails(bank);
        });

        TradeParty buyer = new TradeParty(
                require(data, BusinessTerm.BT_44),
                data.value(BusinessTerm.BT_50).orElse(null),
                data.value(BusinessTerm.BT_53).orElse(null),
                data.value(BusinessTerm.BT_52).orElse(null),
                data.value(BusinessTerm.BT_55).orElse(null));
        data.value(BusinessTerm.BT_48).ifPresent(buyer::addVATID);
        data.value(BusinessTerm.BT_46).ifPresent(buyer::setID);
        data.value(BusinessTerm.BT_51).ifPresent(buyer::setAdditionalAddress);
        data.value(BusinessTerm.BT_49).ifPresent(buyer::setEmail);
        Optional<String> bName = data.value(BusinessTerm.BT_56);
        Optional<String> bPhone = data.value(BusinessTerm.BT_57);
        Optional<String> bMail = data.value(BusinessTerm.BT_58);
        if (bName.isPresent() || bPhone.isPresent() || bMail.isPresent()) {
            buyer.setContact(new Contact(bName.orElse(null), bPhone.orElse(null), bMail.orElse(null)));
        }

        Invoice invoice = new Invoice()
                .setNumber(require(data, BusinessTerm.BT_1))
                .setIssueDate(toDate(requireDate(data, BusinessTerm.BT_2)))
                .setSender(seller)
                .setRecipient(buyer)
                .setCurrency(data.value(BusinessTerm.BT_5).orElse(gen.defaultCurrency()));

        data.date(BusinessTerm.BT_9).ifPresent(d -> invoice.setDueDate(toDate(d)));
        data.date(BusinessTerm.BT_72).ifPresent(d -> invoice.setDeliveryDate(toDate(d)));
        Optional<LocalDate> from = data.date(BusinessTerm.BT_73);
        Optional<LocalDate> to = data.date(BusinessTerm.BT_74);
        if (from.isPresent() && to.isPresent()) {
            invoice.setDetailedDeliveryPeriod(toDate(from.get()), toDate(to.get()));
        }
        data.value(BusinessTerm.BT_10).ifPresent(invoice::setReferenceNumber);
        data.value(BusinessTerm.BT_11).ifPresent(invoice::setSpecifiedProcuringProjectID);
        data.value(BusinessTerm.BT_12).ifPresent(invoice::setContractReferencedDocument);
        data.value(BusinessTerm.BT_13).ifPresent(invoice::setBuyerOrderReferencedDocumentID);
        data.value(BusinessTerm.BT_20).ifPresent(invoice::setPaymentTermDescription);
        data.value(BusinessTerm.BT_22).ifPresent(invoice::addNote);
        data.value(BusinessTerm.BT_25).ifPresent(invoice::setInvoiceReferencedDocumentID);
        data.value(BusinessTerm.BT_83).ifPresent(invoice::setPaymentReference);
        data.decimal(BusinessTerm.BT_113).filter(v -> v.signum() != 0).ifPresent(invoice::setTotalPrepaidAmount);
        data.decimal(BusinessTerm.BT_114).filter(v -> v.signum() != 0).ifPresent(invoice::setRoundingAmount);

        if (data.documentType() == DocumentType.CREDIT_NOTE) {
            invoice.setCreditNote();
        }
        String typeCode = gen.invoiceTypeCodes().get(data.documentType() == DocumentType.CREDIT_NOTE ? "credit-note" : "invoice");
        invoice.setDocumentCode(typeCode != null ? typeCode : data.documentType().defaultTypeCode());

        if (data.lines().isEmpty()) {
            throw new GenerationException("Keine Rechnungspositionen vorhanden");
        }
        boolean standardRate = data.vatCategoryCode() == null || "S".equals(data.vatCategoryCode());
        for (LineItemData line : data.lines()) {
            String name = line.value(BusinessTerm.BT_153)
                    .orElseThrow(() -> new GenerationException("Position " + line.lineNumber() + ": Artikelbezeichnung (BT-153) fehlt"));
            String unit = line.value(BusinessTerm.BT_130).orElse(gen.defaultUnitCode());
            BigDecimal rate = line.value(BusinessTerm.BT_152).map(BigDecimal::new)
                    .or(() -> data.decimal(BusinessTerm.BT_119))
                    .orElse(BigDecimal.ZERO);
            if (!standardRate) {
                rate = BigDecimal.ZERO;
            }
            Product product = new Product(name, line.value(BusinessTerm.BT_154).orElse(""), unit, rate);
            line.value(BusinessTerm.BT_155).ifPresent(product::setSellerAssignedID);
            if (!standardRate) {
                product.setTaxCategoryCode(data.vatCategoryCode());
                if (data.exemptionReasonText() != null) {
                    product.setTaxExemptionReason(data.exemptionReasonText());
                }
            }
            BigDecimal price = line.value(BusinessTerm.BT_146).map(BigDecimal::new)
                    .orElseThrow(() -> new GenerationException("Position " + line.lineNumber() + ": Einzelpreis (BT-146) fehlt"));
            BigDecimal quantity = line.value(BusinessTerm.BT_129).map(BigDecimal::new)
                    .orElseThrow(() -> new GenerationException("Position " + line.lineNumber() + ": Menge (BT-129) fehlt"));
            Item item = new Item(product, price, quantity);
            item.setId(line.value(BusinessTerm.BT_126).orElse(String.valueOf(line.lineNumber())));
            line.value(BusinessTerm.BT_127).ifPresent(item::addNote);
            line.value(BusinessTerm.BT_149).map(BigDecimal::new).ifPresent(item::setBasisQuantity);
            invoice.addItem(item);
        }
        return invoice;
    }

    private static String require(InvoiceData data, BusinessTerm bt) throws GenerationException {
        return data.value(bt).orElseThrow(() -> new GenerationException(bt.id() + " (" + bt.germanLabel() + ") fehlt"));
    }

    private static LocalDate requireDate(InvoiceData data, BusinessTerm bt) throws GenerationException {
        return data.date(bt).orElseThrow(() -> new GenerationException(bt.id() + " (" + bt.germanLabel() + ") fehlt"));
    }

    static Date toDate(LocalDate d) {
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
