package ru.mail.polis.service.httprest.utils;

import org.jetbrains.annotations.NotNull;
import java.util.List;
import com.google.common.base.Splitter;

public class RF {

    public final int ask;
    public final int from;

    /**
     * Replicas entity ask and form
     * @param ask is parameters ask
     * @param from is parameters from
     */
    public RF(final int ask, final int from) {
        this.ask = ask;
        this.from = from;
    }

    @NotNull
    public static RF of( final String value,@NotNull final int nodesSize ) {
        if (value != null) {
            final List<String> values = Splitter.on('/').splitToList(value);
            if (values.size() != 2) {
                throw new IllegalArgumentException("Wrong replica factor:" + value);
            }
            return new RF(Integer.parseInt(values.get(0)), Integer.parseInt(values.get(1)));
        } else {
            return new RF(nodesSize/2 +1,nodesSize );
        }
    }
}
