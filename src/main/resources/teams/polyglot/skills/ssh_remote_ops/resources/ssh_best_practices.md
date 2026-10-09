# SSH & Remote Ubuntu Administration Best Practices

1. **Idempotent Operations**: Use declarative commands (`systemctl restart`, `apt-get install -y --no-install-recommends`).
2. **Pre-Flight Validation**: Test configuration syntax before reloading services (`nginx -t`, `sshd -t`, `apache2ctl configtest`).
3. **Session Re-use**: Maintain named persistent aliases (`default`, `staging`, `prod`) to prevent connection churn.
4. **Log Diagnostics**: Use non-paging flags (`journalctl -u service -n 50 --no-pager`) to avoid blocking standard I/O streams.
