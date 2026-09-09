import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const auth = {
  login: vi.fn(),
  provider: { id: 'gitlab', displayName: 'GitLab', authorizationUrl: '/oauth2/authorization/gitlab' },
};
vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => auth }));

const LoginPage = (await import('../LoginPage')).default;

afterEach(() => {
  window.history.replaceState({}, '', '/');
  auth.provider = { id: 'gitlab', displayName: 'GitLab', authorizationUrl: '/oauth2/authorization/gitlab' };
  vi.clearAllMocks();
});

describe('LoginPage', () => {
  it('offers the sign-in without an error banner', () => {
    render(<LoginPage />);

    expect(screen.getByRole('button', { name: /sign in with gitlab/i })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('starts the OAuth2 redirect when clicked', async () => {
    const user = userEvent.setup();
    render(<LoginPage />);

    await user.click(screen.getByRole('button', { name: /sign in with gitlab/i }));

    expect(auth.login).toHaveBeenCalled();
  });

  it('names the provider the administrator selected', () => {
    auth.provider = { id: 'github', displayName: 'GitHub', authorizationUrl: '/oauth2/authorization/github' };
    render(<LoginPage />);

    // Hard-coding GitLab here would send GitHub instances to a host they never configured
    expect(screen.getByRole('button', { name: /sign in with github/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /gitlab/i })).not.toBeInTheDocument();
  });

  it('says the account is not allowed when the login was refused', () => {
    window.history.replaceState({}, '', '/?error=login_denied');
    render(<LoginPage />);

    expect(screen.getByRole('alert')).toHaveTextContent(/does not have access/i);
  });

  it('distinguishes a failed sign-in from a refused account', () => {
    window.history.replaceState({}, '', '/?error=login_failed');
    render(<LoginPage />);

    expect(screen.getByRole('alert')).toHaveTextContent(/did not complete/i);
    expect(screen.getByRole('alert')).not.toHaveTextContent(/administrator/i);
  });
});
