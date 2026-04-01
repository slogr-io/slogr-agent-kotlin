Name:           slogr-agent
Version:        1.0.0
Release:        1%{?dist}
Summary:        Slogr Network Measurement Agent
License:        Proprietary
URL:            https://slogr.io
BuildArch:      x86_64

Requires:       java-21-amazon-corretto-headless

%description
Slogr Agent — TWAMP-based active network measurement agent for the Slogr
monitoring platform. Measures RTT, jitter, packet loss, and path topology
using RFC 5357 TWAMP (Two-Way Active Measurement Protocol).

%install
install -d %{buildroot}/opt/slogr/{lib,data,logs}
install -d %{buildroot}/usr/bin
install -d %{buildroot}/etc/systemd/system
install -d %{buildroot}/etc/slogr

install -m 644 slogr-agent-all.jar  %{buildroot}/opt/slogr/slogr-agent-all.jar
install -m 755 libslogr-native.so   %{buildroot}/opt/slogr/lib/libslogr-native.so
install -m 755 wrapper.sh           %{buildroot}/usr/bin/slogr-agent
install -m 644 slogr-agent.service  %{buildroot}/etc/systemd/system/slogr-agent.service
install -m 600 /dev/null            %{buildroot}/etc/slogr/env

%pre
getent group  slogr >/dev/null || groupadd -r slogr
getent passwd slogr >/dev/null || \
    useradd -r -g slogr -d /opt/slogr -s /sbin/nologin slogr

%post
chown -R slogr:slogr /opt/slogr
systemctl daemon-reload
systemctl enable slogr-agent

%preun
if [ "$1" = "0" ]; then
    systemctl stop    slogr-agent || true
    systemctl disable slogr-agent || true
fi

%postun
if [ "$1" = "0" ]; then
    systemctl daemon-reload
fi

%files
/opt/slogr/slogr-agent-all.jar
/opt/slogr/lib/libslogr-native.so
%attr(0755, root, root) /usr/bin/slogr-agent
%attr(0644, root, root) /etc/systemd/system/slogr-agent.service
%attr(0600, slogr, slogr) /etc/slogr/env
%dir %attr(0755, slogr, slogr) /opt/slogr/data
%dir %attr(0755, slogr, slogr) /opt/slogr/logs

%changelog
* Tue Apr 01 2026 Slogr Engineering <eng@slogr.io> - 1.0.0-1
- Initial R1 release
