import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import { AuthProvider, useAuth } from './contexts/AuthContext.jsx'
import { WorkspaceProvider } from './contexts/WorkspaceContext.jsx'
import { ToastProvider } from './contexts/ToastContext.jsx'
import ToastContainer from './components/Toast.jsx'
import LoginPage from './components/LoginPage.jsx'
import './index.css'

function AuthGate() {
  const { isAuthenticated, loading } = useAuth();

  if (loading) {
    return (
      <div style={{
        width: '100vw',
        height: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f9fafb',
        color: '#6b7280',
        fontSize: '1rem'
      }}>
        Loading...
      </div>
    );
  }

  if (!isAuthenticated) {
    return <LoginPage />;
  }

  return (
    <WorkspaceProvider>
      <App />
    </WorkspaceProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ToastProvider>
      <AuthProvider>
        <AuthGate />
        <ToastContainer />
      </AuthProvider>
    </ToastProvider>
  </React.StrictMode>,
)
