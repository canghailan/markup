package cc.whohow.markup;

import cc.whohow.markup.impl.CustomHanLPAnalyzer;
import cc.whohow.markup.impl.HanLPPinyinTokenFilter;
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.dictionary.py.Pinyin;
import com.hankcs.hanlp.dictionary.py.PinyinDictionary;
import com.hankcs.lucene.HanLPTokenizer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

import java.io.StringReader;
import java.util.List;

public class TestLucene {
    @Test
    public void testAnalyzer() throws Exception {
        String content = "\n" +
                "[（一）用JAVA编写MP3解码器——前言](https://lfp001.iteye.com/blog/739585)";
        try (Analyzer analyzer = new CustomHanLPAnalyzer()) {
            try (TokenStream tokenStream = analyzer.tokenStream("content", content)) {
                CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);
                tokenStream.reset();
                while (tokenStream.incrementToken()) {
                    System.out.println(charTermAttribute.toString());
                }
            }
        }
    }

    @Test
    public void testTokenizer() throws Exception {
        String content = "\n" +
                "[（一）用JAVA编写MP3解码器——前言](https://lfp001.iteye.com/blog/739585)";
        try (Tokenizer tokenizer = new HanLPTokenizer(HanLP.newSegment().enableIndexMode(true), null, false)) {
            tokenizer.setReader(new StringReader(content));
            CharTermAttribute charTermAttribute = tokenizer.addAttribute(CharTermAttribute.class);
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                System.out.println(charTermAttribute.toString());
            }
        }
    }

    @Test
    public void testPinyinTokenizer() throws Exception {
        String content = "\n" +
                "[（一）用JAVA编写MP3解码器——前言](https://lfp001.iteye.com/blog/739585)";
        Tokenizer tokenizer = new HanLPTokenizer(HanLP.newSegment().enableIndexMode(true), null, false);
        tokenizer.setReader(new StringReader(content));
        try (TokenStream tokenStream = new HanLPPinyinTokenFilter(tokenizer)) {
            TypeAttribute typeAttribute = tokenizer.addAttribute(TypeAttribute.class);
            CharTermAttribute charTermAttribute = tokenizer.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAttribute = tokenizer.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute positionIncrementAttribute = tokenizer.addAttribute(PositionIncrementAttribute.class);
            tokenizer.reset();
            while (tokenStream.incrementToken()) {
                System.out.println(typeAttribute.type());
                System.out.println(charTermAttribute.toString());
                System.out.println(offsetAttribute.startOffset() + "-" + offsetAttribute.endOffset());
                System.out.println(positionIncrementAttribute.getPositionIncrement());
                System.out.println();
            }
        }
    }

    @Test
    public void testHanLP() throws Exception {
        List<Pinyin> pinyinList = PinyinDictionary.convertToPinyin("用JAVA编写MP3解码器——前言", false);
        StringBuilder pinyin = new StringBuilder();
        StringBuilder pinyin1 = new StringBuilder();
        for (Pinyin p : pinyinList) {
            pinyin.append(p.getPinyinWithoutTone());
            pinyin1.append(p.getFirstChar());
        }
        System.out.println(pinyin);
        System.out.println(pinyin1);
    }
}
