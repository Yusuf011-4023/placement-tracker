package com.placementtracker.mail;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Converts HTML to plain text, keeping line breaks for br, p and block elements. */
public final class HtmlToText {

	private static final String BREAK = "\u0001";

	private HtmlToText() {
	}

	public static String convert(String html) {
		if (html == null || html.isBlank()) {
			return "";
		}
		Document doc = Jsoup.parse(html);
		doc.select("script, style, head").remove();
		doc.select("br").after(BREAK);
		for (Element e : doc.select("p, div, li, tr, h1, h2, h3, h4, h5, h6")) {
			e.append(BREAK);
		}
		return doc.body().text().replace(BREAK, "\n").replaceAll(" ?\n ?", "\n").replaceAll("\n{3,}", "\n\n").strip();
	}
}
