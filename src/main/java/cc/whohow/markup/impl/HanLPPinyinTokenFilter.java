package cc.whohow.markup.impl;

import com.hankcs.hanlp.dictionary.py.Pinyin;
import com.hankcs.hanlp.dictionary.py.PinyinDictionary;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;

public final class HanLPPinyinTokenFilter extends TokenFilter {
    private final TypeAttribute typeAttribute = addAttribute(TypeAttribute.class);
    private final CharTermAttribute charTermAttribute = addAttribute(CharTermAttribute.class);
    private final List<Function<List<Pinyin>, ? extends CharSequence>> fns = new ArrayList<>();
    private final Queue<CharSequence> queue = new ArrayDeque<>();
    private boolean origin = true;

    public HanLPPinyinTokenFilter(TokenStream input) {
        super(input);
        fns.add(new ToPinyinString());
        fns.add(new ToPinyinFirstCharString());
    }

    @Override
    public boolean incrementToken() throws IOException {
        while (true) {
            CharSequence term = queue.poll();
            if (term != null) {
                typeAttribute.setType("pinyin");
                charTermAttribute.setEmpty().append(term);
                return true;
            }
            if (input.incrementToken()) {
                List<Pinyin> pinyin = PinyinDictionary.convertToPinyin(charTermAttribute.toString(), false);
                if (!pinyin.isEmpty()) {
                    for (Function<List<Pinyin>, ? extends CharSequence> fn : fns) {
                        CharSequence pinyinTerm = fn.apply(pinyin);
                        if (pinyinTerm != null && pinyinTerm.length() > 0) {
                            queue.add(pinyinTerm);
                        }
                    }
                }
                if (origin) {
                    return true;
                }
            } else {
                return false;
            }
        }
    }

    private static class ToPinyinString implements Function<List<Pinyin>, CharSequence> {
        private StringBuilder buffer = new StringBuilder(32);

        @Override
        public CharSequence apply(List<Pinyin> pinyinList) {
            buffer.setLength(0);
            for (Pinyin p : pinyinList) {
                buffer.append(p.getPinyinWithoutTone());
            }
            return buffer;
        }
    }

    private static class ToPinyinFirstCharString implements Function<List<Pinyin>, CharSequence> {
        private StringBuilder buffer = new StringBuilder(8);

        @Override
        public CharSequence apply(List<Pinyin> pinyinList) {
            buffer.setLength(0);
            for (Pinyin p : pinyinList) {
                buffer.append(p.getFirstChar());
            }
            return buffer;
        }
    }
}
