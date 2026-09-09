import { createContext, useContext, useState, useEffect, useCallback } from 'react';

const AuthContext = createContext(null);

function getCookie(name) {
  const match = document.cookie.match(new RegExp('(^|; )' + name + '=([^;]+)'));
  return match ? decodeURIComponent(match[2]) : null;
}

/** Used until /api/auth/provider answers, and if it never does. */
const DEFAULT_PROVIDER = {
  id: 'gitlab',
  displayName: 'GitLab',
  authorizationUrl: '/oauth2/authorization/gitlab',
};

/**
 * Auth Provider - OAuth2 via Spring Security session.
 * Checks /api/me on mount to restore session, and asks the backend which Git
 * host the administrator selected — login() must go to that one, not a fixed one.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [provider, setProvider] = useState(DEFAULT_PROVIDER);

  useEffect(() => {
    fetch('/api/me', { credentials: 'include' })
      .then(res => res.ok ? res.json() : null)
      .then(data => setUser(data))
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    // Public: the login page needs it before anyone is signed in
    fetch('/api/auth/provider', { credentials: 'include' })
      .then(res => res.ok ? res.json() : null)
      .then(data => {
        if (data?.authorizationUrl) setProvider(data);
      })
      .catch(() => {});
  }, []);

  const login = useCallback(() => {
    window.location.href = provider.authorizationUrl;
  }, [provider]);

  const logout = useCallback(async () => {
    const csrf = getCookie('XSRF-TOKEN');
    await fetch('/logout', {
      method: 'POST',
      credentials: 'include',
      headers: csrf ? { 'X-XSRF-TOKEN': csrf } : {}
    }).catch(() => {});
    setUser(null);
    window.location.href = '/';
  }, []);

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: user !== null, loading, provider, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
}
