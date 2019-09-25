package android.text;

import androidx.annotation.NonNull;

import java.util.Iterator;

/**
 * Because TextUtils is a android library it cannot be accessed in Unit Tests.
 * Adding this class makes the code use this class instead of the one in android,
 * only when running tests and not in actual execution.
 *
 * This problem is acknowledged by Google in 2016 but no solution so far.
 */
public class TextUtils {
    public static String join(@NonNull CharSequence delimiter, @NonNull Iterable tokens) {
        final Iterator<?> it = tokens.iterator();
        if (!it.hasNext()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(it.next());
        while (it.hasNext()) {
            sb.append(delimiter);
            sb.append(it.next());
        }
        return sb.toString();
    }
}
