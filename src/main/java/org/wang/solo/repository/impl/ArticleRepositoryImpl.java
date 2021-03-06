/*
 * Solo - A small and beautiful blogging system written in Java.
 * Copyright (c) 2010-2018, b3log.org & hacpai.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.wang.solo.repository.impl;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.inject.Inject;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.annotation.Repository;
import org.wang.solo.cache.ArticleCache;
import org.wang.solo.model.Article;
import org.wang.solo.repository.ArticleRepository;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Article repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.1.3.14, Sep 16 , 2018
 * @since 0.3.1
 */
@Repository
public class ArticleRepositoryImpl extends AbstractRepository implements ArticleRepository {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleRepositoryImpl.class);

    /**
     * Random range.
     */
    private static final double RANDOM_RANGE = 0.1D;

    /**
     * Article cache.
     */
    @Inject
    private ArticleCache articleCache;

    /**
     * Public constructor.
     */
    public ArticleRepositoryImpl() {
        super(Article.ARTICLE);
    }

    @Override
    public void remove(final String id) throws RepositoryException {
        super.remove(id);

        articleCache.removeArticle(id);
    }

    @Override
    public JSONObject get(final String id) throws RepositoryException {
        JSONObject ret = articleCache.getArticle(id);
        if (null != ret) {
            return ret;
        }

        ret = super.get(id);
        if (null == ret) {
            return null;
        }

        articleCache.putArticle(ret);

        return ret;
    }

    @Override
    public void update(final String id, final JSONObject article) throws RepositoryException {
        super.update(id, article);

        article.put(Keys.OBJECT_ID, id);
        articleCache.putArticle(article);
    }

    @Override
    public JSONObject getByAuthorId(final String authorId, final int currentPageNum, final int pageSize)
            throws RepositoryException {
        final Query query = new Query().
                setFilter(CompositeFilterOperator.and(
                        new PropertyFilter(Article.ARTICLE_AUTHOR_ID, FilterOperator.EQUAL, authorId),
                        new PropertyFilter(Article.ARTICLE_IS_PUBLISHED, FilterOperator.EQUAL, true))).
                addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING).addSort(Article.ARTICLE_PUT_TOP, SortDirection.DESCENDING).
                setCurrentPageNum(currentPageNum).setPageSize(pageSize).setPageCount(1);

        return get(query);
    }

    @Override
    public JSONObject getByPermalink(final String permalink) throws RepositoryException {
        JSONObject ret = articleCache.getArticleByPermalink(permalink);
        if (null != ret) {
            return ret;
        }

        final Query query = new Query().
                setFilter(new PropertyFilter(Article.ARTICLE_PERMALINK, FilterOperator.EQUAL, permalink)).
                setPageCount(1);

        final JSONObject result = get(query);
        final JSONArray array = result.optJSONArray(Keys.RESULTS);
        if (0 == array.length()) {
            return null;
        }

        ret = array.optJSONObject(0);
        articleCache.putArticle(ret);

        return ret;
    }

    @Override
    public List<JSONObject> getRecentArticles(final int fetchSize) throws RepositoryException {
        final Query query = new Query().
                setFilter(new PropertyFilter(Article.ARTICLE_IS_PUBLISHED, FilterOperator.EQUAL, true)).
                addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING).
                setCurrentPageNum(1).setPageSize(fetchSize).setPageCount(1);

        return getList(query);
    }

    @Override
    public List<JSONObject> getMostCommentArticles(final int num) throws RepositoryException {
        final Query query = new Query().
                addSort(Article.ARTICLE_COMMENT_COUNT, SortDirection.DESCENDING).
                addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING).
                setFilter(new PropertyFilter(Article.ARTICLE_IS_PUBLISHED, FilterOperator.EQUAL, true)).
                setCurrentPageNum(1).setPageSize(num).setPageCount(1);

        return getList(query);
    }

    @Override
    public List<JSONObject> getMostViewCountArticles(final int num) throws RepositoryException {
        final Query query = new Query().
                addSort(Article.ARTICLE_VIEW_COUNT, SortDirection.DESCENDING).
                addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING).
                setFilter(new PropertyFilter(Article.ARTICLE_IS_PUBLISHED, FilterOperator.EQUAL, true)).
                setCurrentPageNum(1).setPageSize(num).setPageCount(1);

        return getList(query);
    }

    @Override
    public JSONObject getPreviousArticle(final String articleId) throws RepositoryException {
        final JSONObject currentArticle = get(articleId);
        final long currentArticleCreated = currentArticle.optLong(Article.ARTICLE_CREATED);

        final Query query = new Query().
                setFilter(CompositeFilterOperator.and(
                        new PropertyFilter(Article.ARTICLE_CREATED, FilterOperator.LESS_THAN, currentArticleCreated),
                        new PropertyFilter(Article.ARTICLE_IS_PUBLISHED, FilterOperator.EQUAL, true))).
                addSort(Article.ARTICLE_CREATED, SortDirection.DESCENDING).
                setCurrentPageNum(1).setPageSize(1).setPageCount(1).
                addProjection(Article.ARTICLE_TITLE, String.class).
                addProjection(Article.ARTICLE_PERMALINK, String.class).
                addProjection(Article.ARTICLE_ABSTRACT, String.class);

        final JSONObject result = get(query);
        final JSONArray array = result.optJSONArray(Keys.RESULTS);
        if (1 != array.length()) {
            return null;
        }

        final JSONObject ret = new JSONObject();
        final JSONObject article = array.optJSONObject(0);

        try {
            ret.put(Article.ARTICLE_TITLE, article.getString(Article.ARTICLE_TITLE));
            ret.put(Article.ARTICLE_PERMALINK, article.getString(Article.ARTICLE_PERMALINK));
            ret.put(Article.ARTICLE_ABSTRACT, article.getString((Article.ARTICLE_ABSTRACT)));
        } catch (final JSONException e) {
            throw new RepositoryException(e);
        }

        return ret;
    }

    @Override
    public JSONObject getNextArticle(final String articleId) throws RepositoryException {
        final JSONObject currentArticle = get(articleId);
        final long currentArticleCreated = currentArticle.optLong(Article.ARTICLE_CREATED);

        final Query query = new Query().
                setFilter(CompositeFilterOperator.and(
                        new PropertyFilter(Article.ARTICLE_CREATED, FilterOperator.GREATER_THAN, currentArticleCreated),
                        new PropertyFilter(Article.ARTICLE_IS_PUBLISHED, FilterOperator.EQUAL, true))).
                addSort(Article.ARTICLE_CREATED, SortDirection.ASCENDING).
                setCurrentPageNum(1).setPageSize(1).setPageCount(1).
                addProjection(Article.ARTICLE_TITLE, String.class).
                addProjection(Article.ARTICLE_PERMALINK, String.class).
                addProjection(Article.ARTICLE_ABSTRACT, String.class);

        final JSONObject result = get(query);
        final JSONArray array = result.optJSONArray(Keys.RESULTS);
        if (1 != array.length()) {
            return null;
        }

        final JSONObject ret = new JSONObject();
        final JSONObject article = array.optJSONObject(0);

        try {
            ret.put(Article.ARTICLE_TITLE, article.getString(Article.ARTICLE_TITLE));
            ret.put(Article.ARTICLE_PERMALINK, article.getString(Article.ARTICLE_PERMALINK));
            ret.put(Article.ARTICLE_ABSTRACT, article.getString((Article.ARTICLE_ABSTRACT)));
        } catch (final JSONException e) {
            throw new RepositoryException(e);
        }

        return ret;
    }

    @Override
    public boolean isPublished(final String articleId) throws RepositoryException {
        final JSONObject article = get(articleId);
        if (null == article) {
            return false;
        }

        return article.optBoolean(Article.ARTICLE_IS_PUBLISHED);
    }

    @Override
    public List<JSONObject> getRandomly(final int fetchSize) throws RepositoryException {
        final List<JSONObject> ret = new ArrayList();

        if (0 == count()) {
            return ret;
        }

        final double mid = Math.random() + RANDOM_RANGE;

        LOGGER.log(Level.TRACE, "Random mid[{0}]", mid);

        Query query = new Query().
                setFilter(CompositeFilterOperator.and(
                        new PropertyFilter(Article.ARTICLE_RANDOM_DOUBLE, FilterOperator.GREATER_THAN_OR_EQUAL, mid),
                        new PropertyFilter(Article.ARTICLE_RANDOM_DOUBLE, FilterOperator.LESS_THAN_OR_EQUAL, mid),
                        new PropertyFilter(Article.ARTICLE_IS_PUBLISHED, FilterOperator.EQUAL, true))).
                setCurrentPageNum(1).setPageSize(fetchSize).setPageCount(1);

        final List<JSONObject> list1 = getList(query);
        ret.addAll(list1);

        final int reminingSize = fetchSize - list1.size();
        if (0 != reminingSize) { // Query for remains
            query = new Query();
            query.setFilter(
                    CompositeFilterOperator.and(
                            new PropertyFilter(Article.ARTICLE_RANDOM_DOUBLE, FilterOperator.GREATER_THAN_OR_EQUAL, 0D),
                            new PropertyFilter(Article.ARTICLE_RANDOM_DOUBLE, FilterOperator.LESS_THAN_OR_EQUAL, mid),
                            new PropertyFilter(Article.ARTICLE_IS_PUBLISHED, FilterOperator.EQUAL, true))).
                    setCurrentPageNum(1).setPageSize(reminingSize).setPageCount(1);

            final List<JSONObject> list2 = getList(query);

            ret.addAll(list2);
        }

        return ret;
    }
}
