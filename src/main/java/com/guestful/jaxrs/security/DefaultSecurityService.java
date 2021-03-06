/**
 * Copyright (C) 2013 Guestful (info@guestful.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.guestful.jaxrs.security;

import com.guestful.jaxrs.security.realm.Realm;
import com.guestful.jaxrs.security.realm.UnsupportedTokenException;
import com.guestful.jaxrs.security.session.*;
import com.guestful.jaxrs.security.subject.Subject;
import com.guestful.jaxrs.security.subject.SubjectContext;
import com.guestful.jaxrs.security.token.AuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.security.auth.login.LoginException;
import java.security.Principal;
import java.util.stream.Stream;

/**
 * date 2014-05-26
 *
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
@Singleton
public class DefaultSecurityService implements SecurityService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSecurityService.class);

    @Inject SessionRepository sessionRepository;
    @Inject Realm realm;

    @PostConstruct
    public void register() {
        LOGGER.trace("register() {}: realm={}, sessionRepository={}", getClass().getSimpleName(), realm, sessionRepository.getClass().getSimpleName());
        SubjectContext.setSecurityService(this);
    }

    @Override
    public void accessed(Subject subject) {
        if (subject.getPrincipal() != null
            && subject.getSession(false) != null
            && subject.getAuthenticationToken() != null
            && subject.getAuthenticationToken().isSessionAllowed()) {
            Session session = subject.getSession();
            if (!session.isExpired()) {
                LOGGER.trace("accessed() {} - session {}", subject, subject.getSession());
                sessionRepository.saveSession(subject.getSystem(), StoredSession.accessed(subject));
            }
        }
    }

    @Override
    public Subject login(AuthenticationToken token, LoginContext loginContext) throws LoginException {
        if (!realm.supports(token)) {
            throw new UnsupportedTokenException(token.getClass().getName());
        }
        LOGGER.trace("login() {}", token);
        Subject subject = realm.authenticate(token, loginContext);
        if (token.isAuthenticationRequired()) {
            if (subject.getPrincipal().equals(loginContext.getPrincipal()) && loginContext.getSession(false) != null) {
                LOGGER.trace("login() {} - removing old session {} of subject {}", token, loginContext.getSession(), subject);
                sessionRepository.removeSession(subject.getSystem(), loginContext.getSession().getId());
            }
        }
        return subject;
    }

    @Override
    public void logout(Subject subject) {
        if (subject.getPrincipal() != null && subject.getSession(false) != null && subject.getAuthenticationToken() != null) {
            if (realm.supports(subject.getAuthenticationToken())) {
                realm.onLogout(subject);
            }
            if (subject.getAuthenticationToken().isSessionAllowed()) {
                Session session = subject.getSession();
                LOGGER.trace("logout() {} - session {}", subject, session);
                sessionRepository.removeSession(subject.getSystem(), session.getId());
            }
        }
    }

    @Override
    public Stream<ConnectedSession> getConnectedSessions(String system, Principal principal) {
        LOGGER.trace("getConnectedSessions() {}:{}", system, principal);
        return sessionRepository.findSessions(system)
            .filter(stored -> {
                if (!stored.getPrincipal().equals(principal.getName())) {
                    return false;
                }
                if (stored.isExpired()) {
                    sessionRepository.removeSession(system, stored.getId());
                    return false;
                }
                return true;
            })
            .map(stored -> new DefaultConnectedSession(stored) {
                @Override
                public void invalidate() {
                    sessionRepository.removeSession(system, getId());
                }
            });
    }

    @Override
    public Stream<ConnectedSession> getConnectedSessions(String system) {
        LOGGER.trace("getConnectedSessions() {}", system);
        return sessionRepository.findSessions(system)
            .filter(stored -> {
                if (stored.isExpired()) {
                    sessionRepository.removeSession(system, stored.getId());
                    return false;
                }
                return true;
            })
            .map(stored -> new DefaultConnectedSession(stored) {
                @Override
                public void invalidate() {
                    sessionRepository.removeSession(system, getId());
                }
            });
    }

}
