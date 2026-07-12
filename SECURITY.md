# Security Policy

D2OS is designed for regulated, compliance-sensitive work — we take security reports seriously.

## Reporting a vulnerability

**Please do not open a public issue for a security vulnerability.**

Instead, report it privately via GitHub's **[Private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)**
(the **Security** tab → *Report a vulnerability*). If that is unavailable, contact the maintainers
through a private channel rather than a public issue.

When reporting, please include:

- A description of the vulnerability and its impact
- Steps to reproduce (a minimal proof of concept if possible)
- Affected version/commit and configuration (e.g. default posture vs. OIDC mode)

We aim to acknowledge reports within a few business days and to keep you informed as we investigate
and remediate.

## Supported versions

This project is pre-1.0; security fixes are applied to the default branch (`main`). Pin a commit and
watch releases if you depend on it.

## Security posture (what to know)

- **Secrets are fail-loud** — required credentials (DB passwords, `D2OS_JWT_SECRET`, storage secret
  key) have no defaults; the app refuses to start when one is unset. Never commit real secrets;
  `.env`, `*.key`, and `*.pem` are gitignored.
- **Tenant isolation is defense-in-depth** — a workspace-scoped token plus Postgres Row-Level Security
  bound per connection via a least-privilege role.
- **Optional OIDC + RBAC** — enabling `d2os.security.oidc.enabled` adds per-user identity (verified
  RS256/ES256 against the IdP's JWKS) and role enforcement; trust-sensitive decisions are recorded in
  a tamper-evident, hash-chained audit trail.
- **Default-deny cross-boundary movement** — promotion to the global library is blocked until it
  clears an automated sensitivity filter and a human governance gate.

Responsible disclosure is appreciated — thank you for helping keep D2OS and its users safe.
