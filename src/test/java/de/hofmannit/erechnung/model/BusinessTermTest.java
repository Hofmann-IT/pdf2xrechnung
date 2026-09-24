package de.hofmannit.erechnung.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BusinessTermTest {

    @Test
    void idsAreUniqueAndWellFormed() {
        Set<String> ids = new HashSet<>();
        for (BusinessTerm bt : BusinessTerm.values()) {
            assertThat(bt.id()).matches("BT-\\d{1,3}");
            assertThat(ids.add(bt.id())).as("doppelte ID %s", bt.id()).isTrue();
            assertThat(bt.name()).isEqualTo(bt.id().replace('-', '_'));
        }
    }

    @Test
    void lookupIsLenient() {
        assertThat(BusinessTerm.fromId("BT-1")).contains(BusinessTerm.BT_1);
        assertThat(BusinessTerm.fromId(" bt-131 ")).contains(BusinessTerm.BT_131);
        assertThat(BusinessTerm.fromId("BT_44")).contains(BusinessTerm.BT_44);
        assertThat(BusinessTerm.fromId("BT-999")).isEmpty();
        assertThat(BusinessTerm.fromId(null)).isEmpty();
    }

    @Test
    void requiredTermsFromSpecificationArePresent() {
        for (String id : new String[] {"BT-1", "BT-2", "BT-3", "BT-5", "BT-9", "BT-20", "BT-10", "BT-24", "BT-27",
                "BT-31", "BT-44", "BT-81", "BT-106", "BT-107", "BT-108", "BT-109", "BT-110", "BT-111", "BT-112",
                "BT-113", "BT-114", "BT-115", "BT-118", "BT-119", "BT-126"}) {
            assertThat(BusinessTerm.fromId(id)).as(id).isPresent();
        }
        assertThat(BusinessTerm.BT_126.isLineLevel()).isTrue();
        assertThat(BusinessTerm.BT_115.isLineLevel()).isFalse();
    }
}
