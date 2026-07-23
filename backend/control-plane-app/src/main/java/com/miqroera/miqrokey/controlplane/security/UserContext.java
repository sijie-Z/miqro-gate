package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserSession;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped holder for the currently authenticated user and session.
 */
@Component
@RequestScope
public class UserContext {

    private User user;
    private UserSession session;

    public User getUser() {
        return user;
    }
    public void setUser(User user) {
        this.user = user;
    }
    public UserSession getSession() {
        return session;
    }
    public void setSession(UserSession session) {
        this.session = session;
    }
    public boolean isAuthenticated() {
        return user != null;
    }
}
