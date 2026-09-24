package de.hofmannit.erechnung.testsupport;

/** Hilfsprogramm für den Timeout-Test der Prozessausführung: schläft die angegebene Zeit. */
public final class Sleeper {

    private Sleeper() {
    }

    public static void main(String[] args) throws InterruptedException {
        long millis = args.length > 0 ? Long.parseLong(args[0]) : 30000;
        System.out.println("sleeping " + millis);
        Thread.sleep(millis);
    }
}
