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
package org.wang.solo;

import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.event.EventManager;
import org.b3log.latke.ioc.LatkeBeanManager;
import org.b3log.latke.ioc.Lifecycle;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.plugin.PluginManager;
import org.b3log.latke.plugin.ViewLoadEventHandler;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.latke.servlet.AbstractServletListener;
import org.b3log.latke.util.Requests;
import org.b3log.latke.util.Stopwatchs;
import org.b3log.latke.util.Strings;
import org.json.JSONObject;
import org.wang.solo.event.*;
import org.wang.solo.model.Option;
import org.wang.solo.model.Skin;
import org.wang.solo.repository.OptionRepository;
import org.wang.solo.repository.impl.OptionRepositoryImpl;
import org.wang.solo.service.*;
import org.wang.solo.util.Skins;
import org.wang.solo.util.Solos;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletRequestEvent;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Solo Servlet listener.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.9.3.40, Sep 26, 2018
 * @since 0.3.1
 */
public final class SoloServletListener extends AbstractServletListener {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(SoloServletListener.class);

    /**
     * Solo version.
     */
    public static final String VERSION = "2.9.4";

    /**
     * Bean manager.
     */
    private LatkeBeanManager beanManager;

    /**
     * Request lock.
     */
    private Lock requestLock = new ReentrantLock();

    @Override
    public void contextInitialized(final ServletContextEvent servletContextEvent) {
        Latkes.USER_AGENT = Solos.USER_AGENT;
        Latkes.setScanPath("org.wang.solo");
        super.contextInitialized(servletContextEvent);
        Stopwatchs.start("Context Initialized");

        beanManager = Lifecycle.getBeanManager();

        // Upgrade check https://github.com/b3log/solo/issues/12040
        final UpgradeService upgradeService = beanManager.getReference(UpgradeService.class);
        upgradeService.upgrade();

        // Import check https://github.com/b3log/solo/issues/12293
        final ImportService importService = beanManager.getReference(ImportService.class);
        importService.importMarkdowns();

        JdbcRepository.dispose();

        final OptionRepository optionRepository = beanManager.getReference(OptionRepositoryImpl.class);
        final Transaction transaction = optionRepository.beginTransaction();
        try {
            loadPreference();

            if (transaction.isActive()) {
                transaction.commit();
            }
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
        }

        registerEventHandlers();

        final PluginManager pluginManager = beanManager.getReference(PluginManager.class);
        pluginManager.load();

        LOGGER.info("Solo is running [" + Latkes.getServePath() + "]");

        Stopwatchs.end();
        LOGGER.log(Level.DEBUG, "Stopwatch: {0}{1}", Strings.LINE_SEPARATOR, Stopwatchs.getTimingStat());
    }

    @Override
    public void contextDestroyed(final ServletContextEvent servletContextEvent) {
        super.contextDestroyed(servletContextEvent);

        LOGGER.info("Destroyed the context");
    }

    @Override
    public void sessionCreated(final HttpSessionEvent httpSessionEvent) {
    }

    @Override
    public void sessionDestroyed(final HttpSessionEvent httpSessionEvent) {
        super.sessionDestroyed(httpSessionEvent);
    }

    @Override
    public void requestInitialized(final ServletRequestEvent servletRequestEvent) {
        requestLock.lock();

        final HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequestEvent.getServletRequest();
        Requests.log(httpServletRequest, Level.DEBUG, LOGGER);

        final String requestURI = httpServletRequest.getRequestURI();
        Stopwatchs.start("Request Initialized [requestURI=" + requestURI + "]");
        if (Requests.searchEngineBotRequest(httpServletRequest)) {
            LOGGER.log(Level.DEBUG, "Request made from a search engine [User-Agent={0}]", httpServletRequest.getHeader("User-Agent"));
            httpServletRequest.setAttribute(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT, true);
        } else {
            // Gets the session of this request
            final HttpSession session = httpServletRequest.getSession();

            LOGGER.log(Level.DEBUG, "Gets a session [id={0}, remoteAddr={1}, User-Agent={2}, isNew={3}]", session.getId(),
                    httpServletRequest.getRemoteAddr(), httpServletRequest.getHeader("User-Agent"), session.isNew());
            // Online visitor count
            final StatisticMgmtService statisticMgmtService = beanManager.getReference(StatisticMgmtService.class);

            statisticMgmtService.onlineVisitorCount(httpServletRequest);
        }

        resolveSkinDir(httpServletRequest);
    }

    @Override
    public void requestDestroyed(final ServletRequestEvent servletRequestEvent) {
        try {
            Stopwatchs.end();

            LOGGER.log(Level.DEBUG, "Stopwatch: {0}{1}", Strings.LINE_SEPARATOR, Stopwatchs.getTimingStat());
            Stopwatchs.release();

            super.requestDestroyed(servletRequestEvent);
        } finally {
            requestLock.unlock();
        }
    }

    /**
     * Loads preference.
     * <p>
     * Loads preference from repository, loads skins from skin directory then sets it into preference if the skins
     * changed.
     * </p>
     */
    private void loadPreference() {
        Stopwatchs.start("Load Preference");

        LOGGER.debug("Loading preference....");

        final PreferenceQueryService preferenceQueryService = beanManager.getReference(PreferenceQueryService.class);
        JSONObject preference;

        try {
            preference = preferenceQueryService.getPreference();
            if (null == preference) {
                LOGGER.info("Please open browser and visit [" + Latkes.getServePath() + "] to init your Solo, "
                        + "and then enjoy it :-p");

                return;
            }

            final PreferenceMgmtService preferenceMgmtService = beanManager.getReference(PreferenceMgmtService.class);

            preferenceMgmtService.loadSkins(preference);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            throw new IllegalStateException(e);
        }

        Stopwatchs.end();
    }

    /**
     * Register event handlers.
     */
    private void registerEventHandlers() {
        Stopwatchs.start("Register Event Handlers");
        LOGGER.debug("Registering event handlers....");

        try {
            final EventManager eventManager = beanManager.getReference(EventManager.class);
            final ArticleCommentReplyNotifier articleCommentReplyNotifier = beanManager.getReference(ArticleCommentReplyNotifier.class);
            eventManager.registerListener(articleCommentReplyNotifier);
            final PageCommentReplyNotifier pageCommentReplyNotifier = beanManager.getReference(PageCommentReplyNotifier.class);
            eventManager.registerListener(pageCommentReplyNotifier);
            final PluginRefresher pluginRefresher = beanManager.getReference(PluginRefresher.class);
            eventManager.registerListener(pluginRefresher);
            eventManager.registerListener(new ViewLoadEventHandler());
            final B3ArticleSender articleSender = beanManager.getReference(B3ArticleSender.class);
            eventManager.registerListener(articleSender);
            final B3ArticleUpdater articleUpdater = beanManager.getReference(B3ArticleUpdater.class);
            eventManager.registerListener(articleUpdater);
            final B3CommentSender commentSender = beanManager.getReference(B3CommentSender.class);
            eventManager.registerListener(commentSender);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Register event handlers error", e);
            throw new IllegalStateException(e);
        }

        LOGGER.debug("Registered event handlers");
        Stopwatchs.end();
    }

    /**
     * Resolve skin (template) for the specified HTTP servlet request.
     *
     * @param httpServletRequest the specified HTTP servlet request
     */
    private void resolveSkinDir(final HttpServletRequest httpServletRequest) {
        // https://github.com/b3log/solo/issues/12060
        httpServletRequest.setAttribute(Keys.TEMAPLTE_DIR_NAME, Option.DefaultPreference.DEFAULT_SKIN_DIR_NAME);
        final Cookie[] cookies = httpServletRequest.getCookies();
        if (null != cookies) {
            for (final Cookie cookie : cookies) {
                if (Skin.SKIN.equals(cookie.getName())) {
                    final String skin = cookie.getValue();
                    final Set<String> skinDirNames = Skins.getSkinDirNames();

                    if (skinDirNames.contains(skin)) {
                        httpServletRequest.setAttribute(Keys.TEMAPLTE_DIR_NAME, skin);

                        return;
                    }
                }
            }
        }

        try {
            final PreferenceQueryService preferenceQueryService = beanManager.getReference(PreferenceQueryService.class);
            final JSONObject preference = preferenceQueryService.getPreference();
            if (null == preference) { // Not initialize yet
                return;
            }

            final String requestURI = httpServletRequest.getRequestURI();

            String desiredView = Requests.mobileSwitchToggle(httpServletRequest);
            if (desiredView == null && !Requests.mobileRequest(httpServletRequest) || desiredView != null && desiredView.equals("normal")) {
                desiredView = preference.getString(Skin.SKIN_DIR_NAME);
            } else {
                desiredView = Solos.MOBILE_SKIN;
                LOGGER.log(Level.DEBUG, "The request [URI={0}] via mobile device", requestURI);
            }

            httpServletRequest.setAttribute(Keys.TEMAPLTE_DIR_NAME, desiredView);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Resolves skin failed", e);
        }
    }
}
