package cc.whohow.markup.impl;

import java.util.function.Predicate;

public class PrefixFilter implements Predicate<String> {
    private String prefix;

    public PrefixFilter(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public boolean test(String s) {
        return s.startsWith(prefix);
    }
}
