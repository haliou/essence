package io.github.cdimascio.unfluff

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import javax.xml.soap.Text

val REGEX_BAD_TAGS = """^side$|combx|retweet|mediaarticlerelated|menucontainer|navbar|partner-gravity-ad|video-full-transcript|storytopbar-bucket|utility-bar|inline-share-tools|comment|PopularQuestions|contact|foot|footer|Footer|footnote|cnn_strycaptiontxt|cnn_html_slideshow|cnn_strylftcntnt|links|meta$|shoutbox|sponsor|tags|socialnetworking|socialNetworking|cnnStryHghLght|cnn_stryspcvbx|^inset$|pagetools|post-attributes|welcome_form|contentTools2|the_answers|communitypromo|runaroundLeft|subscribe|vcard|articleheadings|date|^print$|popup|author-dropdown|tools|socialtools|byline|konafilter|KonaFilter|breadcrumbs|^fn$|wp-caption-text|legende|ajoutVideo|timestamp|js_replies""".toRegex(RegexOption.IGNORE_CASE)

class Cleaner(private val doc: Document, private val language: Language) {
    private var d = doc
    fun clean(): CleanDocument {
        removeBodyClasses()
        cleanArticleTags()
        cleanEmTags()
        cleanCodeBlocks()
        removeDropCaps()
        removeScriptsStyles()
        Traverse(
            nodeRemovalRules = listOf(
                TraversalRules::removeCommentsTravRule,
                TraversalRules::removeBadTagsTravRule,
                (TraversalRules::removeMatching)("""^caption$""".toRegex()),
                (TraversalRules::removeMatching)(""" google """.toRegex()),
                (TraversalRules::removeMatching)("""^[^entry-]more.*$""".toRegex()),
                (TraversalRules::removeMatching)("""[^-]facebook""".toRegex()),
                (TraversalRules::removeMatching)("""facebook-broadcasting""".toRegex()),
                (TraversalRules::removeMatching)("""[^-]twitter""".toRegex())),
            nodeModificationRules = listOf(
                TraversalRules::correctErrantLineBreaks
//                (TraversalRules::tagsToParagraph)(listOf("div", "span", "a"))
            ))
            .applyRules(d)
            .purgeMarkedNodes()
        cleanParaSpans()
        cleanUnderlines()

//        elementToParagraph(d, "div")
//        elementToParagraph(d, "span")

        return CleanDocument(
            text = d.html(),
            language = language
        )
    }

    /**
     * Remove all classes
     */
    private fun removeBodyClasses() {
        val body = d.body()
        body.classNames().forEach {
            body.removeClass(it)
        }
    }

    private fun cleanArticleTags() {
        val articles = d.getElementsByTag("article")
        articles.forEach {
            it.removeAttr("id")
            it.removeAttr("name")
        }
    }

    private fun cleanEmTags() {
        val ems = d.getElementsByTag("em")
        ems.forEach {
            val images = ems.select("img")
            if (images.isEmpty()) {
                it.replaceWith(Element(it.html()))
            }
        }
    }

    private fun cleanCodeBlocks() {
        val nodes = d.select(
            "[class*='highlight-'], pre code, code, pre, ul.task-list"
        )
        nodes.forEach {
            it.replaceWith(TextNode(it.text()))
        }
    }

    private fun removeDropCaps() {
        val nodes = d.select("span[class~=dropcap], span[class~=drop_cap]")
        return nodes.forEach {
            return it.replaceWith(Element(it.html()))
        }
    }

    private fun removeScriptsStyles() {
        d.getElementsByTag("script").remove()
        d.getElementsByTag("style").remove()
    }

    private fun cleanParaSpans() {
        d.select("p span").forEach {
            val html = it.html()
            if (html.isNullOrBlank()) {
                it.replaceWith(TextNode(""))
            } else {
                it.replaceWith(Element(html))
            }
        }
    }

    private fun cleanUnderlines() {
        d.select("u").forEach {
            it.replaceWith(Element(it.html()))
        }
    }

//    private fun elementToParagraph(doc: Document, tagName: String) {
//        val elements = doc.select(tagName)
//        val tags = listOf("a", "blockquote", "dl", "div", "img", "ol", "p", "pre", "table", "ul")
//        for (element in elements) {
//            val items = element.select(tags.joinToString(","))
//            if (items.isEmpty()) {
//                val content = element.html()
//                element.replaceWith(Element("<p>$content</p>"))
//            } else {
////                val replacementNodes = ReplacementNodes.find(doc, element)
//                // change all replacement nodes to paragraph tags
////                replacementNodes.forEach{ node ->
////                    if (node is Element) node.tagName("p")
////                }
////                element.empty()
////                if (html.isNotBlank())
////                    element.replaceWith(Element(html))
////            }
//        }
//    }
}

class Traverse(val nodeRemovalRules: List<(Node) -> Boolean>, val nodeModificationRules: List<(Node) -> Unit>) {
    private var nodesToRemove = setOf<Node>()
    fun applyRules(node: Node): Traverse {
        for (child in node.childNodes()) {
            var nodeMarkedForRemoval = false
            for (rule in nodeRemovalRules) {
                if (rule(child)) {
                    nodeMarkedForRemoval = true
                    nodesToRemove += child
                    break
                }
            }
            if (nodeMarkedForRemoval) continue

            for (rule in nodeModificationRules) {
                rule(child)
            }
            applyRules(child)
        }
        return this
    }

    /**
     * purge nodes marked for deletion
     */
    fun purgeMarkedNodes() {
        nodesToRemove.forEach{
            it.remove()
        }
    }

}

object TraversalRules {
    /**
     * Remove node if a comment nodes and return true, else return false
     */
    fun removeCommentsTravRule(node: Node) = node.nodeName() == "#comment"

    fun removeBadTagsTravRule(node: Node) =
        node.attr("id").matches(REGEX_BAD_TAGS) ||
            node.attr("class").matches(REGEX_BAD_TAGS) ||
            node.attr("name").matches(REGEX_BAD_TAGS)

    fun removeMatching(re: Regex): (Node) -> Boolean {
        return { node: Node ->
            node.attr("id").matches(re) ||
                node.attr("class").matches(re)
        }
    }

    fun correctErrantLineBreaks(node: Node) {
        if (node is Element && node.tagName() == "p") {
            for (textNode in node.textNodes()) {
                val text = textNode.text().replace("""([^\n])\n([^\n])""".toRegex()) {
                    it.groupValues.joinToString(" ")
                }
                textNode.text(text)
            }
        }
    }

    fun tagsToParagraph(tags: List<String>): (Node) -> Unit {
        return { node ->
            val itemTags = listOf("a", "blockquote", "dl", "div", "img", "ol", "p", "pre", "table", "ul")
            if (node is Element && tags.contains(node.tagName())) {
                if (node.text().isNotBlank()) {
                    node.tagName("p")
                }
            }
        }
    }
}
//
//object ReplacementNodes {
//    fun find(document: Document, div: Element): List<Node> {
//        var replacementText = mutableListOf<String>()
//        var nodesToReturn = mutableListOf<Node>()
//        var nodesToRemove = mutableListOf<Node>()
//        for (child in div.childNodes()) {
//            when {
//                child is Element && child.tagName() == "p" -> {
//                    val text = replacementText.joinToString("")
//                    nodesToReturn.add(TextNode(text))
//                    replacementText.clear()
//                    nodesToReturn.add(Element(child.html()))
//                }
//                child is TextNode -> {
//                    val ATTR_GRV_USED_ALREADY = "grv-usedalready"
//                    val YES = "yes"
//                    val text = child.text()
//                    val replaceText = text
//                        .replace("\\n", "\\n\\n")
//                        .replace("\\t", "")
//                        .replace("^\\s+$", "")
//                    if (replaceText.isNotBlank()) {
//                        var prevSibling = child.previousSibling()
//                        while (prevSibling is Element && prevSibling.tagName() == "a" && prevSibling.attr(ATTR_GRV_USED_ALREADY) != YES) {
//                            val outer = " ${prevSibling.outerHtml()} "
//                            replacementText.add(outer)
//                            nodesToRemove.add(prevSibling)
//                            prevSibling.attr(ATTR_GRV_USED_ALREADY, YES)
//                            prevSibling = prevSibling.previousElementSibling()
//                        }
//                        replacementText.add(replaceText)
//
//                        var nextSibling = child.nextSibling()
//                        while (nextSibling is Element && nextSibling.tagName() == "a" && nextSibling.attr(ATTR_GRV_USED_ALREADY) != YES) {
//                            val outer = " ${nextSibling.outerHtml()} "
//                            replacementText.add(outer)
//                            nodesToRemove.add(nextSibling)
//                            nextSibling.attr(ATTR_GRV_USED_ALREADY, YES)
//                            nextSibling = nextSibling.nextElementSibling()
//                        }
//                    }
//                }
//                child is Element -> {
//                    nodesToReturn.add(child)
////                    // this just remove the parent, can we do this more efficiently
////                    val html = child.html()
////                    if (html.isNotBlank())
////                        nodesToReturn.add(Element(html))
//                }
//                child is TextNode -> {
//                    nodesToReturn.add(child)
////                    val text = child.text()
////                    if (text.isNotBlank())
////                        nodesToReturn.add(TextNode(text))
//                }
//                else -> {
//                    nodesToReturn.add(child)
//                    println("shouldn't get here - missed a case")
//                    /* nothing */
//                }
//            }
//        }
//
//        if (replacementText.isNotEmpty()) {
//            val text = replacementText.joinToString("")
//            nodesToReturn.add(TextNode(text))
//            replacementText.clear()
//        }
//
//        nodesToRemove.forEach {
//            it.remove()
//        }
//        return nodesToReturn
//    }
//}