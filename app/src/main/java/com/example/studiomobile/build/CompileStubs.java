package com.example.studiomobile.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Compile-time-only stubs for JDK classes that ECJ needs to resolve when compiling at Java 17
 * but that android.jar does not contain:
 * - java.lang.invoke.StringConcatFactory / StringConcatException (string concatenation, Java 9+)
 * - java.lang.invoke.LambdaMetafactory / LambdaConversionException (lambdas and method references)
 * - java.lang.runtime.ObjectMethods / java.lang.invoke.TypeDescriptor and java.lang.Record (records, Java 16+)
 * If android.jar already contains one of these, android.jar comes first on the classpath and wins.
 *
 * The stubs are put on the compiler classpath only. They are never packaged: D8 desugars the
 * invokedynamic call sites for the app's minSdk.
 */
final class CompileStubs {
    private CompileStubs() { }

    // Base64 of a small jar built from the stub sources (compiled with --release 8).
    private static final String JAR_BASE64 =
            "UEsDBAoAAAgAAO1TTl0AAAAAAAAAAAAAAAAJAAQATUVUQS1JTkYv/soAAFBLAwQUAAgICADtU05dAAAAAAAAAAAAAAAAFAAAAE1F" +
            "VEEtSU5GL01BTklGRVNULk1G803My0xLLS7RDUstKs7Mz7NSMNQz4OVyLkpNLElN0XWqtFIwAoroGRrpGSpohCaV5pWUavJy8XIB" +
            "AFBLBwhT+4TlOgAAADkAAABQSwMECgAACAAA7VNOXQAAAAAAAAAAAAAAAAUAAABqYXZhL1BLAwQKAAAIAADtU05dAAAAAAAAAAAA" +
            "AAAACgAAAGphdmEvbGFuZy9QSwMEFAAICAgA7VNOXQAAAAAAAAAAAAAAABYAAABqYXZhL2xhbmcvUmVjb3JkLmNsYXNzXU+7bsJA" +
            "EJzFh83DvBJSpkhnU0CTjihNJCQkFKSAKNKdzQnOMrYwNv+VCilFPoCPQqzthnAn7eyNZmZvz5ffPwCveGygAsOCsFGFSegG8ihH" +
            "oYw2o7kXKD8lmG860uk7wXDclYXaP9GX8uNkTRAf8VoROjMdqc9s56lkKb2QGVPtMxkeCE/O7D577H4Talt52JZuHjBlIo0XaaKj" +
            "DaHvuDemkh0TGos4S3w10fmAZvmFYa4TL7B4n/wIvrwRCHV+PTMSY3VwAv1wwyFczYI0BKfAZg+hhXaBHXQLVY9rBQ9XUEsHCN/w" +
            "XsLhAAAAMAEAAFBLAwQKAAAIAADtU05dAAAAAAAAAAAAAAAAEQAAAGphdmEvbGFuZy9pbnZva2UvUEsDBBQACAgIAO1TTl0AAAAA" +
            "AAAAAAAAAAAwAAAAamF2YS9sYW5nL2ludm9rZS9MYW1iZGFDb252ZXJzaW9uRXhjZXB0aW9uLmNsYXNzdZDNTgIxFIXPZf5kHEUl" +
            "LsW4w8E4G3cYN0RXEzcQ9x1osAqtGYfB13I1iQsewIcy3gIJhGgX9/S23zm96ffP1wLADZohanACuBE8+ITmiyhFMhF6nNx/DOVb" +
            "oYwm+LdKq+KO4LQvn6xjz+J1wmk73Rj6Ra70uLsi9i0REeI/iK2TwXNu5iKbSLYFOGR+c6d0aV5lkoppNhI9o0uZv/M4W3O5PTOS" +
            "hEaqtHycTTOZD2wWIeybWT6UD8o2rX8Tru1juMABD2yXA7LfwLXB3RkrsXpxBfrkDeGIq788XKH1NdriABvhx50KwS7rLtlozZ6z" +
            "12EN4s5VhXAX9rges9Zw8gtQSwcINuiF7AEBAACmAQAAUEsDBBQACAgIAO1TTl0AAAAAAAAAAAAAAAAoAAAAamF2YS9sYW5nL2lu" +
            "dm9rZS9MYW1iZGFNZXRhZmFjdG9yeS5jbGFzc7VTXU8TQRQ90y1d+oGAH2j9QESIwIONiW81JqUuuHGRpIsk6oOZdkdc2M5sdmcb" +
            "+QMm/hyfSDTRd+NvMt4pUVYaqjHxZe/cM+fsnLn3ztfvHz8DuI/lCgqwbBRrmECJYWafD3gj4nKvsd3dFz3NUHoQylA/ZLBWVndt" +
            "TDLcOSE9k2kWxyrRItiORcJ1qKTztidis6jANv+uMiyeKEI5UAei4fF+N+BbQvPXvKdVcsgwu+G1Nl/5Tsdtee6L1rrnMDCXYaqt" +
            "ZKq51Ls8yoRFvhlDbUjeanWeOB3fYIWf2HrHfbTpDLEiQ7GtAsEw7YVSPM36XZHs8G5ESLWfP/vbijdikcy9UcFjLoNIpEueUgdZ" +
            "3MzRfJ2Ecq95lnDnMBb/tnl85Hjt6uhum0eRH2rRZKj86kFq4yrD2hn1p8oORJLmm8Zwjkf6t8a8+0/FeemdnrY/XctXWdITG6Fp" +
            "4NzICN01WhoDV0qRtCOepoKuf4th+a/s27jNMD+eSs/hmIx7ME8HsFBHDVOgupkpxDTlM7l8lvLzufwCKQrmsRFykZAbFBnFibUj" +
            "sA9muHGJvqUhaKGMOVwmgaEuUCxRnPwE+/kRyl9Oscu4Yoae1nW8xzVcz+mKY3TVnK5AhozNedykWCFsAYtYQv0HUEsHCOkBByfo" +
            "AQAAMwQAAFBLAwQUAAgICADtU05dAAAAAAAAAAAAAAAALAAAAGphdmEvbGFuZy9pbnZva2UvU3RyaW5nQ29uY2F0RXhjZXB0aW9u" +
            "LmNsYXNzO/Vv1z4GBgYTBgEuBiYGZnYGFh4GVgY2RgbhrMSyRP2cxLx0fdeK5NSCksz8PEYGNpvMvMwSO0YGUQ0fhILgkqLMvHRr" +
            "zTCQGRwgAzgZGbSwqEASCckoyi9PTMpJBWpjZ+BmZFBDyGXmleVnp0I1OefnJSeWILmBxTk/JZWRgd8nMy/VrzQ3KbUoBGQOIwNX" +
            "cH5pUXKqWyaII4VVtx7IEgZFBpBDQYCJgRHkXSDJA+TJAfkgcTYt7e0MjBuBLEYGXhAfSDMwMIPVckLVygP5zECaXUtbZzsDO7pi" +
            "FiDJB7aAHwBQSwcIpapxZeMAAABjAQAAUEsDBBQACAgIAO1TTl0AAAAAAAAAAAAAAAAqAAAAamF2YS9sYW5nL2ludm9rZS9TdHJp" +
            "bmdDb25jYXRGYWN0b3J5LmNsYXNztVK5TgMxFJyXa0M4wn2DKLgLIiS6IJoIBFKAIhxCVM7GApPFXu06CD6BP+A3qJBAgp6PQrwl" +
            "EokIBBq28Izfztge+72+PT4DWMNUBjHEHSS6kESK0HshrkTOE/ost1++kK4lpNaVVnaDEF9cOnKQJiw0RIc6rPm+Cays7PsyEFYZ" +
            "vXntSj8iGTjR2p2E2YZD6StTlbmSDZQ+KxjtCrslXGuCG0KiYCqSkC0qLfdql2UZHIiyx5XMpajKupgQLhZbltuV9txUtoWueDKc" +
            "LRpTrfn5Jll9u/xPxoMbX+aXWv8WhOeVlJV5PsJnrNBBP2G+baRPMWGkcfZjZc+ZhVZoGxLu/ilIq/y0+PVdf0tbMrXAlVsquv3R" +
            "bx5rJXITuna0lkHBE2Eo+V7GCHN/iuRggjDdXsqtVxdjFVGbRl+ckRsVvDPPphiJMbn8ALpnQujmMfVRjKMDPciyIZLORH3OmH6C" +
            "c/KAjpcv6iR6eUww78MtBjDY5Eu28aWbfDEMMY9hGCOMGa6NYhyTGHsHUEsHCM4P0eCMAQAAbwMAAFBLAwQUAAgICADtU05dAAAA" +
            "AAAAAAAAAAAAJQAAAGphdmEvbGFuZy9pbnZva2UvVHlwZURlc2NyaXB0b3IuY2xhc3M79W/XPgYGBhMGTnYGJkYG+azEskT9nMS8" +
            "dP3MvLL87FT9kMqCVJfU4uSizIKS/CJ2BhZGBgGEIv+krNTkEqBQClxJcElRZl46I4OIhqYPQiFE1JqRgSs4v7QoOdUtMyeVkUEY" +
            "1XQ9kHo2RgZGBmYGEGAEWsbKwAZiMbADSSYGDgBQSwcI8c9fcoEAAACwAAAAUEsDBAoAAAgAAO1TTl0AAAAAAAAAAAAAAAASAAAA" +
            "amF2YS9sYW5nL3J1bnRpbWUvUEsDBBQACAgIAO1TTl0AAAAAAAAAAAAAAAAlAAAAamF2YS9sYW5nL3J1bnRpbWUvT2JqZWN0TWV0" +
            "aG9kcy5jbGFzc71SSUtDMRD+xtY+rUvd9+2guBwsgreKFxcUqh5aBfGUtqFGa/LIy3P5Cf4cQRAU9O6PEuc9xbohHsSEZCbJN9/w" +
            "Tebx6fYewCJG02hAwkOyFY1IETqOxKnI1oSuZndKR7LsCKklpZVbJiRmZvc8NBGm66BdHYS+b6yTlR1fWuGU0WvnZelHThpexN1C" +
            "GK9H2FA7dSJf6bekOzSVgJBcMRVJyOSVltvhSUnaoijV+Ka5ZIwLnBU+4WomXydS+tQcy+wLw4bQlZoMJvPGHId+7h2s4KzS1dzX" +
            "wOKFL1dlULbKd8a+B6zURBB8w3Hwc/bcbP5z9XKE9Fs5Ag9dhO46pnhozdmryoKqauFCy/71P6pcmlv+M6EFE9qyXFeRnq4P3zsf" +
            "wQmtm1pLG+eVXIxBwtSvhHoYJoz9DOU+fQFjAVFPR6OBJ3c1ODOfRtkS28a5G9AVO4Q23lPxZRKXaEcGiRg6wYEptk138PZv0Pzw" +
            "Ce2hI44hdKI7TtTDqzf2+tDPNs1vAxjCCAafAVBLBwh157CgiQEAAGwDAABQSwECCgAKAAAIAADtU05dAAAAAAAAAAAAAAAACQAE" +
            "AAAAAAAAAAAAAAAAAAAATUVUQS1JTkYv/soAAFBLAQIUABQACAgIAO1TTl1T+4TlOgAAADkAAAAUAAAAAAAAAAAAAAAAACsAAABN" +
            "RVRBLUlORi9NQU5JRkVTVC5NRlBLAQIKAAoAAAgAAO1TTl0AAAAAAAAAAAAAAAAFAAAAAAAAAAAAAAAAAKcAAABqYXZhL1BLAQIK" +
            "AAoAAAgAAO1TTl0AAAAAAAAAAAAAAAAKAAAAAAAAAAAAAAAAAMoAAABqYXZhL2xhbmcvUEsBAhQAFAAICAgA7VNOXd/wXsLhAAAA" +
            "MAEAABYAAAAAAAAAAAAAAAAA8gAAAGphdmEvbGFuZy9SZWNvcmQuY2xhc3NQSwECCgAKAAAIAADtU05dAAAAAAAAAAAAAAAAEQAA" +
            "AAAAAAAAAAAAAAAXAgAAamF2YS9sYW5nL2ludm9rZS9QSwECFAAUAAgICADtU05dNuiF7AEBAACmAQAAMAAAAAAAAAAAAAAAAABG" +
            "AgAAamF2YS9sYW5nL2ludm9rZS9MYW1iZGFDb252ZXJzaW9uRXhjZXB0aW9uLmNsYXNzUEsBAhQAFAAICAgA7VNOXekBByfoAQAA" +
            "MwQAACgAAAAAAAAAAAAAAAAApQMAAGphdmEvbGFuZy9pbnZva2UvTGFtYmRhTWV0YWZhY3RvcnkuY2xhc3NQSwECFAAUAAgICADt" +
            "U05dpapxZeMAAABjAQAALAAAAAAAAAAAAAAAAADjBQAAamF2YS9sYW5nL2ludm9rZS9TdHJpbmdDb25jYXRFeGNlcHRpb24uY2xh" +
            "c3NQSwECFAAUAAgICADtU05dzg/R4IwBAABvAwAAKgAAAAAAAAAAAAAAAAAgBwAAamF2YS9sYW5nL2ludm9rZS9TdHJpbmdDb25j" +
            "YXRGYWN0b3J5LmNsYXNzUEsBAhQAFAAICAgA7VNOXfHPX3KBAAAAsAAAACUAAAAAAAAAAAAAAAAABAkAAGphdmEvbGFuZy9pbnZv" +
            "a2UvVHlwZURlc2NyaXB0b3IuY2xhc3NQSwECCgAKAAAIAADtU05dAAAAAAAAAAAAAAAAEgAAAAAAAAAAAAAAAADYCQAAamF2YS9s" +
            "YW5nL3J1bnRpbWUvUEsBAhQAFAAICAgA7VNOXXXnsKCJAQAAbAMAACUAAAAAAAAAAAAAAAAACAoAAGphdmEvbGFuZy9ydW50aW1l" +
            "L09iamVjdE1ldGhvZHMuY2xhc3NQSwUGAAAAAA0ADQC3AwAA5AsAAAAA";

    /** Writes the stub jar into {@code directory} (if not already there) and returns it, or null on failure. */
    static File extract(File directory) {
        if (directory == null) return null;
        try {
            byte[] data = Base64.getDecoder().decode(JAR_BASE64);
            File target = new File(directory, "compile-stubs.jar");
            if (target.isFile() && target.length() == data.length) return target;
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            try (FileOutputStream out = new FileOutputStream(target)) { out.write(data); }
            return target;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
