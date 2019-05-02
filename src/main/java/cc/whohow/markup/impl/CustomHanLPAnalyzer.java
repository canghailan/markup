package cc.whohow.markup.impl;

import com.hankcs.hanlp.HanLP;
import com.hankcs.lucene.HanLPTokenizer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class CustomHanLPAnalyzer extends Analyzer {
    private Set<String> filter;
    private boolean enablePorterStemming;
    private List<Function<TokenStream, TokenStream>> filters = Arrays.asList(
            LowerCaseFilter::new,
            HanLPPinyinTokenFilter::new
    );

    public CustomHanLPAnalyzer(Set<String> filter, boolean enablePorterStemming) {
        this.filter = filter;
        this.enablePorterStemming = enablePorterStemming;
    }

    public CustomHanLPAnalyzer(boolean enablePorterStemming) {
        this.enablePorterStemming = enablePorterStemming;
    }

    public CustomHanLPAnalyzer() {
        super();
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new HanLPTokenizer(HanLP.newSegment().enableIndexMode(true), filter, enablePorterStemming);
        TokenStream tokenStream = tokenizer;
        for (Function<TokenStream, TokenStream> filter : filters) {
            tokenStream = filter.apply(tokenStream);
        }
        return new TokenStreamComponents(tokenizer, tokenStream);
    }
}
