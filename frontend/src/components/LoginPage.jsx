import { useAuth } from '../contexts/AuthContext';
import './LoginPage.css';

export default function LoginPage() {
  const { login } = useAuth();

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-header">
          <h1>Flow Designer</h1>
          <p>Visual payment flow editor</p>
        </div>
        <div className="login-body">
          <button className="login-btn login-btn-gitlab" onClick={login}>
            Sign in with GitLab
          </button>
          <p className="login-hint">You will be redirected to your GitLab instance</p>
        </div>
      </div>
    </div>
  );
}
