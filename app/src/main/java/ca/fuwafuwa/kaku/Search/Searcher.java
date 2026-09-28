package ca.fuwafuwa.kaku.Search;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;

import ca.fuwafuwa.kaku.Database.JmDictDatabase.Models.EntryOptimized;
import ca.fuwafuwa.kaku.Database.KanjiDict2Database.Models.CharacterOptimized;

/**
 * Created by 0xbad1d3a5 on 8/28/2016.
 */
public class Searcher implements JmTask.SearchJmTaskDone {

    public interface SearchDictDone
    {
        void jmResultsCallback(List<JmSearchResult> results, SearchInfo search);
    }

    private static final String TAG = Searcher.class.getName();

    private SearchDictDone mSearchDictDone;
    private Context mContext;

    public Searcher(Context context) throws SQLException
    {
        mContext = context;
    }

    public void registerCallback(SearchDictDone dictDone)
    {
        this.mSearchDictDone = dictDone;
    }

    public void unregisterCallback()
    {
        this.mSearchDictDone = null;
    }

    public void search(SearchInfo searchInfo)
    {
        // The imported Yomitan dictionaries answer; 白い熊 画 ships none (upstream's bundled 2019 JMdict
        // is gone), so without one the result window says where to import it.
        if (shiroikuma.kaku.dict.DictDb.get(mContext).hasEnabledTerms()) {
            new YomitanTask(searchInfo, this, mContext).executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
            return;
        }
        String ch = searchInfo.getSquareChar().getChar();
        java.util.List<JmSearchResult> notice = new java.util.ArrayList<>();
        notice.add(new JmSearchResult(new EntryOptimized(), new ca.fuwafuwa.kaku.Deinflictor.DeinflectionInfo(ch, 0, ""), ch,
                null, java.util.Collections.emptyList(), mContext.getString(ca.fuwafuwa.kaku.R.string.no_dictionary_imported)));
        if (mSearchDictDone != null) mSearchDictDone.jmResultsCallback(notice, searchInfo);
    }

    @Override
    public void jmTaskCallback(@NotNull List<JmSearchResult> results, @NotNull SearchInfo searchInfo)
    {
        mSearchDictDone.jmResultsCallback(results, searchInfo);
    }
}
