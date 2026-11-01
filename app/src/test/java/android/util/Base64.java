package android.util;

public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    public static byte[] decode(String value, int flags) {
        return (flags == URL_SAFE ? java.util.Base64.getUrlDecoder()
                : java.util.Base64.getDecoder()).decode(value);
    }

    public static String encodeToString(byte[] value, int flags) {
        return (flags == URL_SAFE ? java.util.Base64.getUrlEncoder()
                : java.util.Base64.getEncoder()).encodeToString(value);
    }
}
