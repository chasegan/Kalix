---
title: Downloads
---

# Downloads

Every release, newest first. Install from PyPI, or download the IDE for your platform.

<div class="kx-releases" markdown>
{% for r in releases %}
<div class="kx-release" markdown>
<div class="kx-release-head">
  <span class="kx-release-ver">v{{ r.version }}</span>
  {% if r.latest %}<span class="kx-pill kx-pill--accent">Latest</span>{% endif %}
  {% if r.prerelease %}<span class="kx-pill">Pre-release</span>{% endif %}
  <span class="kx-release-date">{{ r.date }}</span>
</div>
{% if r.changelog %}
<ul class="kx-release-log">
{% for c in r.changelog %}<li>{{ c }}</li>
{% endfor %}
</ul>
{% endif %}
<div class="kx-release-dl">
  {% if r.assets.windows %}<a href="{{ r.assets.windows }}">Windows&nbsp;.exe</a>{% endif %}
  {% if r.assets.macos %}<a href="{{ r.assets.macos }}">macOS&nbsp;.dmg</a>{% endif %}
  {% if r.assets.linux %}<a href="{{ r.assets.linux }}">Linux&nbsp;.AppImage</a>{% endif %}
  {% if r.assets.docs %}<a href="{{ r.assets.docs }}">Docs&nbsp;.zip</a>{% endif %}
</div>
<code class="kx-release-pip">{{ r.pip }}</code>
</div>
{% endfor %}
</div>

[Older releases on GitHub&nbsp;↗](https://github.com/chasegan/Kalix/releases)

!!! note "How this page stays current"
    This list is generated at build time from the GitHub Releases API (with a committed fallback). Cutting a new GitHub release is all it takes — the version, date, changelog, download links and `pip` command update themselves.
