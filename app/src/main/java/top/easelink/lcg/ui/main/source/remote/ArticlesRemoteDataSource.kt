package top.easelink.lcg.ui.main.source.remote

import android.text.TextUtils
import android.util.ArrayMap
import androidx.annotation.WorkerThread
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import timber.log.Timber
import top.easelink.lcg.network.Client
import top.easelink.lcg.ui.main.model.BlockException
import top.easelink.lcg.ui.main.model.LoginRequiredException
import top.easelink.lcg.ui.main.source.ArticlesDataSource
import top.easelink.lcg.ui.main.source.FavoritesRemoteDataSource
import top.easelink.lcg.ui.main.source.model.*
import top.easelink.lcg.utils.WebsiteConstant
import top.easelink.lcg.utils.WebsiteConstant.FORUM_BASE_URL
import java.util.*

/**
 * author : junzhang
 * date   : 2019-07-04 16:22
 * desc   :
 */
object ArticlesRemoteDataSource: ArticlesDataSource, FavoritesRemoteDataSource {
    private val gson: Gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()

    @WorkerThread
    override fun getForumArticles(
        query: String,
        processThreadList: Boolean
    ): ForumPage? {
        val doc = Client.sendRequestWithQuery(query)
        return processForumArticlesDocument(doc, processThreadList)
    }

    @WorkerThread
    override fun getHomePageArticles(
        param: String,
        pageNum: Int
    ): List<Article> {
        return getArticles("$FORUM_BASE_URL$param&page=$pageNum")
    }

    @Throws(BlockException::class, HttpStatusException::class)
    @WorkerThread
    override fun getPostPreview(query: String): PreviewPost? {
        return try {
            getFirstPost(Client.sendRequestWithQuery(query))
        } catch (e: Exception) {
            when(e) {
                is BlockException,
                is HttpStatusException -> throw e
            }
            Timber.w(e)
            null
        }
    }

    @Throws(BlockException::class)
    @WorkerThread
    override fun getArticleDetail(query: String): ArticleDetail? {
        try {
            val doc = Client.sendRequestWithQuery(query)
            var articleAbstract: ArticleAbstractResponse? = null
            doc.selectFirst("script")?.run {
                try {
                    val json = html().trim().replace("\u00a0".toRegex(), "")
                    articleAbstract = gson.fromJson(json, ArticleAbstractResponse::class.java)
                } catch (e: Exception) {
                    // no need to handle
                }
            }
            val title = doc.selectFirst("span#thread_subject")?.text().orEmpty()
            if (title.isEmpty()) {
                throw BlockException()
            }
            val nextPageUrl = doc.selectFirst("a.nxt")?.attr("href").orEmpty()
            val replyAddUrls = getReplyAddUrl(doc)
            val avatarsAndNames = getAvatarAndName(doc)
            val contents = getContents(doc)
            val dateTimes = getDateTime(doc)
            val replyUrls = getReplyUrls(doc)
            val postList: MutableList<Post> = ArrayList(avatarsAndNames.size)
            for (i in avatarsAndNames.indices) {
                try {
                    val replyAddUrl: String? = if (i >= replyAddUrls.size) {
                        null
                    } else {
                        replyAddUrls[i]
                    }
                    val post = Post(
                        avatarsAndNames[i]["name"].toString(),
                        avatarsAndNames[i]["avatar"].toString(),
                        dateTimes[i],
                        contents[i],
                        replyUrls[i],
                        replyAddUrl
                    )
                    postList.add(post)
                } catch (npe: NullPointerException) {
                    // will skip a loop if there's any npe occurs
                    Timber.v(npe)
                }
            }
            val fromHash = doc.selectFirst("input[name=formhash]")?.attr("value")
            return ArticleDetail(title, postList, nextPageUrl, fromHash, articleAbstract)
        } catch (e: Exception) {
            when(e) {
                is BlockException -> throw e
            }
            Timber.e(e)
            return null
        }
    }

    private fun getArticles(query: String): List<Article> {
        var list: List<Article> = emptyList()
        try {
            list = Client
                .sendRequestWithQuery(query)
                .select("tbody")
                .map {
                    try {
                        val reply = extractFrom(it, "td.num", "a.xi2").toInt()
                        val view = extractFrom(it, "td.num", "em").toInt()
                        val title = extractFrom(it, "th.common", ".xst")
                        val author = extractFrom(it, "td.by", "a[href*=uid]")
                        val date = extractFrom(it, "td.by", "span")
                        val url = extractAttrFrom(it, "href", "th.common", "a.xst")
                        val origin = extractFrom(it, "td.by", "a[target]")
                        if (title.isNotBlank() && author.isNotEmpty()) {
                            return@map Article(title, author, date, url, view, reply, origin)
                        }
                    } catch (nbe: NumberFormatException) {
                        Timber.v(nbe)
                    } catch (e: Exception) {
                        Timber.e(e)
                    }
                    null
                }
                .filterNotNull()
        } catch (e: Exception) {
            Timber.e(e)
        } finally {
            return list
        }
    }

    private fun processForumArticlesDocument(doc: Document, processThreadList: Boolean): ForumPage? {
        try {
            var elements = doc.select("tbody[id^=normal]")
            if (elements.isEmpty()) {
                val element = doc.getElementById("messagelogin")
                if (element != null) {
                    throw LoginRequiredException()
                }
            }
            val articleList: MutableList<Article> = ArrayList()
            for (element in elements) {
                try {
                    val reply = extractFrom(element, "td.num", "a.xi2").toInt()
                    val view = extractFrom(element, "td.num", "em").toInt()
                    val title = extractFrom(element, "th.new", ".xst").also {
                        if (it.isBlank()){
                            extractFrom(element, "th.common", ".xst")
                        }
                    }
                    val author = extractFrom(element, "td.by", "a[href*=uid]")
                    val date = extractFrom(element, "td.by", "span")
                    val url = extractAttrFrom(element, "href", "th.new", "a.xst").also {
                        if (it.isBlank()) {
                            extractAttrFrom(element, "href", "th.common", "a.xst")
                        }
                    }
                    val origin = extractFrom(element, "td.by", "a[target]")
                    if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(author)) {
                        articleList.add(
                            Article(title, author, date, url, view, reply, origin)
                        )
                    }
                } catch (nbe: NumberFormatException) {
                    Timber.v(nbe)
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
            // for thread part
            val threadList: MutableList<ForumThread> =
                ArrayList()
            if (processThreadList) {
                val threadTypes = doc.getElementById("thread_types")
                if (threadTypes != null) {
                    for (elementByTag in threadTypes.getElementsByTag("li")) {
                        try {
                            val element = elementByTag.getElementsByTag("a").first()
                            elements = element.getElementsByTag("span")
                            if (elements.size > 0) {
                                elements.remove()
                            }
                            val threadUrl = element.attr("href")
                            val name = element.text().trim { it <= ' ' }
                            if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(threadUrl)) {
                                threadList.add(ForumThread(name, threadUrl))
                            }
                        } catch (e: Exception) { // don't care
                        }
                    }
                }
            }
            return ForumPage(articleList, threadList)
        } catch (e: Exception) {
            Timber.e(e)
            return null
        }
    }

    private fun extractFrom(element: Element, vararg tags: String): String {
        if (tags.isNullOrEmpty()) {
            return element.text()
        }
        var e = Elements(element)
        for (tag in tags) {
            e = e.select(tag)
            if (e.isEmpty()) {
                break
            }
        }
        return e.text()
    }

    private fun extractAttrFrom(element: Element, attr: String, vararg tags: String): String {
        if (tags.isNullOrEmpty()) {
            return element.text()
        }
        var e = Elements(element)
        for (tag in tags) {
            e = e.select(tag)
            if (e.isEmpty()) {
                break
            }
        }
        return e.attr(attr)
    }

    private fun getReplyAddUrl(document: Document): List<String> {
        val list: MutableList<String> = ArrayList(12)
        try {
            val e = document.getElementById("recommend_add")
            list.add(e.attr("href"))
            val elements = document.select("a.replyadd")
            for (element in elements) {
                list.add(element.attr("href"))
            }
        } catch (npe: NullPointerException) {
            // no need to handle
        }
        return list
    }

    private fun getAvatarAndName(document: Document): List<Map<String, String>> {
        val list: MutableList<Map<String, String>> = ArrayList(12)
        val elements = document.select("td[rowspan]")
        for (element in elements) {
            val avatarAndName: MutableMap<String, String> = ArrayMap(2)
            avatarAndName["avatar"] = element.select("div.avatar").select("img").attr("src")
            avatarAndName["name"] = element.select("a.xw1").text()
            list.add(avatarAndName)
        }
        return list
    }

    private fun getDateTime(document: Document): List<String> {
        return document.select("div.authi").select("em").map {
            it.text()
        }
    }

    private fun getFirstPost(document: Document): PreviewPost {
        val dateTime = document
            .selectFirst("div.authi")
            ?.select("em")
            ?.text()
            ?: throw BlockException()
        val content = getFirstContent(document)
        var avatar: String? = null
        var name: String? = null
        document.selectFirst("td[rowspan]").apply {
            avatar = selectFirst("div.avatar").selectFirst("img").attr("src")
            name = selectFirst("a.xw1").text()
        }
        return PreviewPost(
            avatar = avatar?:"",
            author = name?:"Unknown",
            date = dateTime,
            content = content
        )
    }

    private fun getFirstContent(doc: Document): String {
        return doc.selectFirst("div.pcb")
            .let {
                    element -> element.selectFirst("td.t_f")?.let {
                    tmp -> processContentElement(tmp)
            }
                ?: element.selectFirst("div.locked").html()
            }
    }

    private fun getContents(doc: Document): List<String> {
        return doc.select("div.pcb")
            .filterNotNull()
            .mapTo(ArrayList<String>(), {
                it.selectFirst("td.t_f")?.let {
                        tmp -> processContentElement(tmp)
                } ?: it.selectFirst("div.locked").html()
            })
    }

    private fun getReplyUrls(doc: Document): List<String> {
        return doc.getElementsByClass("fastre").map {
            it.attr("href")
        }
    }

    private fun processContentElement(element: Element): String { // remove picture tips
        element.select("div.tip").remove()
        // remove user level info etc
        element.select("script").remove()
        // convert all code
        for (e in element.getElementsByTag("pre")) {
            e.siblingElements().remove()
            val s =
                e.html().replace("\r\n".toRegex(), "<br/>").replace(" ".toRegex(), "&nbsp;")
            e.html(s)
        }
        val imgElements = element.getElementsByTag("img")
        for (i in imgElements.indices) {
            val imgElement = imgElements[i]
            val src = imgElement.attr("src")
            if (src.contains("https://static.52pojie.cn/static/") && !src.contains("none")) {
                imgElement.remove()
            }
            val attr = imgElement.attr("file")
            if (!TextUtils.isEmpty(attr)) {
                imgElement.attr("src", attr)
            }
        }
        return element.html()
    }

    @WorkerThread
    override fun addFavorites(threadId: String, formHash: String): Boolean {
        return try {
            Client.sendRequestWithQuery(String.format(
                WebsiteConstant.FAVORITE_URL,
                threadId,
                formHash
            ))
            true
        } catch (e: Exception) {
            Timber.e(e)
            false
        }
    }

    /**
     * Support user's post
     * @param query post url without base_server_url
     * @return status message
     */
    @WorkerThread
    fun replyAdd(query: String): String {
        return try {
            val doc = Client.sendRequestWithQuery(query)
            val message = doc.getElementsByClass("nfl").first().text()
            Timber.d(doc.html())
            Timber.d(message)
            message
        } catch (e: Exception) {
            Timber.e(e)
            "Error"
        }
    }
}