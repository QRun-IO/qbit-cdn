# Upgrading

## Unreleased

- No schema change. Hosts get the help text, the Setup card and the new actions automatically.
- The nightly origin check runs on hosts with a scheduler (02:45 UTC) over hostnames that are not Off. It makes HTTPS requests to the provider's origin host (or the hostname) for a few pages; allow that egress or leave hostnames Off.
- Run Create default provider on hosts that have no provider if you want purges recorded as would-be purges.
