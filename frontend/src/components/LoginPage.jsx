import { useAuth } from '../contexts/AuthContext';
import './LoginPage.css';

export default function LoginPage() {
  const { login } = useAuth();

  // Set by the backend: a rejected account, or any other OAuth2 failure
  const loginError = new URLSearchParams(window.location.search).get('error');

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-header">
          <h1>Flow Designer</h1>
          <p>Visual payment flow editor</p>
        </div>
        <div className="login-body">
          {loginError === 'login_denied' && (
            <p className="login-error" role="alert">
              This account does not have access to Flow Designer. Ask an administrator to add it.
            </p>
          )}
          {loginError === 'login_failed' && (
            <p className="login-error" role="alert">
              Sign-in did not complete. Try again — if it keeps failing, the GitLab connection needs checking.
            </p>
          )}
          <button className="login-btn login-btn-gitlab" onClick={login}>
            Sign in with GitLab
          </button>
          <p className="login-hint">You will be redirected to your GitLab instance</p>
        </div>
      </div>
    </div>
  );
}
