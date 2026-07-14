# Security Policy

## Responsible Disclosure

If you discover a security vulnerability, please email the maintainers
directly instead of using the public issue tracker. Include:

- A clear description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested remediation (if applicable)

We will acknowledge your report within 48 hours and provide a timeline
for a fix.

## Security Best Practices

When using this actor in production:

1. **Audit Ledger**: Verify that all Governor decisions are recorded in the
   append-only audit ledger and regularly reviewed.

2. **Scope**: This actor coordinates plant operations metadata. It does NOT
   control processing equipment or override food-safety determinations—
   those remain exclusive to licensed plant operators.

3. **Human-in-the-Loop**: Food-safety-critical proposals (`:flag-food-safety-concern`,
   high-stakes operations like `:log-production-batch` and `:coordinate-shipment`)
   always escalate to human approval.

4. **Governance**: The independent Governor is intentionally decoupled from
   the Advisor. Audit the Governor's violation checks and hold decisions
   regularly.

5. **Data Protection**: Batch records contain sensitive production data.
   Implement proper access controls and data retention policies.

## Supported Versions

Security fixes will be provided for released versions. Development versions
(on `main` branch) receive security fixes as part of regular maintenance.

## Disclaimer

This blueprint is published under AGPL-3.0 for transparency and forkability.
It is not a substitute for regulatory compliance or expert consultation on
food-safety and labor law. Each operator must:

- Comply with local food-safety regulations
- Maintain independent quality assurance processes
- Employ licensed food-safety officers
- Conduct regular audits and traceability reviews

The authors and maintainers provide no warranty regarding food safety,
regulatory compliance, or fitness for production use.
