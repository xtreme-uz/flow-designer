import { createContext, useContext, useState, useEffect, useCallback } from 'react';

const AuthContext = createContext(null);

function getCookie(name) {
  const match = document.cookie.match(new RegExp('(^|; )' + name + '=([^;]+)'));
  return match ? decodeURIComponent(match[2]) : null;
}

/**
 * Auth Provider - GitLab OAuth2 via Spring Security session.
 * Checks /api/me on mount to restore session. login() redirects to GitLab.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch('/api/me', { credentials: 'include' })
      .then(res => res.ok ? res.json() : null)
      .then(data => setUser(data))
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, []);

  const login = useCallback(() => {
    window.location.href = '/oauth2/authorization/gitlab';
  }, []);

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
    <AuthContext.Provider value={{ user, isAuthenticated: user !== null, loading, login, logout }}>
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
