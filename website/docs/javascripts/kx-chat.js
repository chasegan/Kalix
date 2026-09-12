/* Kalix help assistant widget.
 *
 * Mounts inline into #kx-chat-page when that element exists (the Help page),
 * otherwise as a floating button and panel on every page. The transcript lives
 * in sessionStorage so it survives navigation within the docs and nothing
 * else; the server stores nothing.
 */
(() => {
  const ENDPOINT = "https://api.kalix.org/chat";
  const STORE = "kx-chat";
  const MAX_TURNS = 10;

  const STARTERS = [
    "How do I define a storage node?",
    "How do I run a model from Python?",
    "What is the difference between GR4J and Sacramento?",
    "How do I reference input data in an expression?",
  ];

  const FAILURES = {
    budget:
      "The Kalix project has used up this month's AI helper budget. Ask in " +
      "<a href=\"https://github.com/chasegan/Kalix/discussions\">GitHub Discussions</a> " +
      "and a person will answer.",
    busy: "The helper is busy. Try again in a minute.",
    rate_limited: "That's a lot of questions at once. Wait a minute and try again.",
    unavailable: "The helper is not available right now. The docs and " +
      "<a href=\"https://github.com/chasegan/Kalix/discussions\">GitHub Discussions</a> still are.",
  };

  const load = () => {
    try { return JSON.parse(sessionStorage.getItem(STORE)) || []; } catch { return []; }
  };
  const save = (turns) => sessionStorage.setItem(STORE, JSON.stringify(turns));

  // --- rendering -----------------------------------------------------------

  const escape = (s) =>
    s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  /** Just enough Markdown for the assistant's style: fences, inline code, bold, links, paragraphs. */
  const render = (text) => {
    const blocks = [];
    const withFences = text.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
      blocks.push(`<pre><code class="language-${escape(lang)}">${escape(code.trimEnd())}</code></pre>`);
      return `\u0000${blocks.length - 1}\u0000`;
    });
    const inline = escape(withFences)
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2">$1</a>')
      .replace(/(^|[\s(])(https?:\/\/kalix\.org\/[^\s)]*)/g, '$1<a href="$2">$2</a>')
      .replace(/^(?:[-*]) (.*)$/gm, "<li>$1</li>")
      .replace(/(<li>[\s\S]*?<\/li>)(?!\s*<li>)/g, "<ul>$1</ul>");
    const paragraphs = inline
      .split(/\n{2,}/)
      .map((p) => (/^<(ul|pre)/.test(p.trim()) ? p : `<p>${p.trim().replace(/\n/g, "<br>")}</p>`))
      .join("");
    return paragraphs.replace(/\u0000(\d+)\u0000/g, (_, i) => blocks[i]);
  };

  // --- streaming -----------------------------------------------------------

  /** Read an Anthropic SSE stream, calling onText with each text delta. */
  async function consume(body, onText) {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      buffer += decoder.decode(value, { stream: true });
      const events = buffer.split("\n\n");
      buffer = events.pop();
      for (const event of events) {
        const line = event.split("\n").find((l) => l.startsWith("data: "));
        if (!line) continue;
        const data = JSON.parse(line.slice(6));
        if (data.type === "content_block_delta" && data.delta?.type === "text_delta") onText(data.delta.text);
      }
    }
  }

  // --- widget --------------------------------------------------------------

  function mount(root, { inline }) {
    root.classList.add("kx-chat", inline ? "kx-chat--inline" : "kx-chat--floating");
    root.innerHTML = `
      <div class="kx-chat-log" role="log" aria-live="polite"></div>
      <form class="kx-chat-form">
        <textarea rows="1" maxlength="2000" placeholder="Ask about Kalix…" aria-label="Your question"></textarea>
        <button type="submit" aria-label="Send">→</button>
      </form>
      <p class="kx-chat-note">Answers come from the Kalix docs and may be wrong; check the linked page.
        <button type="button" class="kx-chat-reset">Clear</button></p>`;

    const log = root.querySelector(".kx-chat-log");
    const form = root.querySelector("form");
    const input = root.querySelector("textarea");
    const send = root.querySelector("button[type=submit]");
    let turns = load();
    let busy = false;

    const bubble = (role, html) => {
      const el = document.createElement("div");
      el.className = `kx-chat-msg kx-chat-msg--${role}`;
      el.innerHTML = html;
      log.appendChild(el);
      log.scrollTop = log.scrollHeight;
      return el;
    };

    const showStarters = () => {
      const el = bubble("starters", STARTERS.map((s) => `<button type="button">${escape(s)}</button>`).join(""));
      el.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => ask(b.textContent)));
    };

    const redraw = () => {
      log.innerHTML = "";
      if (turns.length === 0) showStarters();
      for (const t of turns) bubble(t.role, t.role === "user" ? escape(t.content) : render(t.content));
    };

    async function ask(question) {
      const content = question.trim();
      if (!content || busy) return;
      busy = true;
      send.disabled = true;
      input.value = "";
      input.style.height = "";

      if (turns.length === 0) log.innerHTML = "";
      turns = [...turns.slice(-(MAX_TURNS - 1)), { role: "user", content }];
      if (turns[0].role !== "user") turns = turns.slice(1);
      bubble("user", escape(content));
      const reply = bubble("assistant", '<span class="kx-chat-dots"></span>');

      try {
        const res = await fetch(ENDPOINT, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ messages: turns }),
        });
        if (!res.ok) {
          const { error } = await res.json().catch(() => ({}));
          reply.className += " kx-chat-msg--error";
          reply.innerHTML = FAILURES[error] || FAILURES.unavailable;
          turns.pop();
          return;
        }
        let text = "";
        await consume(res.body, (delta) => {
          text += delta;
          reply.innerHTML = render(text);
          log.scrollTop = log.scrollHeight;
        });
        turns.push({ role: "assistant", content: text });
        save(turns);
      } catch {
        reply.className += " kx-chat-msg--error";
        reply.innerHTML = FAILURES.unavailable;
        turns.pop();
      } finally {
        busy = false;
        send.disabled = false;
        input.focus();
      }
    }

    form.addEventListener("submit", (e) => { e.preventDefault(); ask(input.value); });
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); ask(input.value); }
    });
    input.addEventListener("input", () => {
      input.style.height = "";
      input.style.height = `${Math.min(input.scrollHeight, 160)}px`;
    });
    root.querySelector(".kx-chat-reset").addEventListener("click", () => {
      turns = [];
      save(turns);
      redraw();
    });

    redraw();
    return { focus: () => input.focus() };
  }

  function mountFloating() {
    const button = document.createElement("button");
    button.className = "kx-chat-fab";
    button.type = "button";
    button.setAttribute("aria-label", "Ask about Kalix");
    button.innerHTML = "<span>Ask</span>";

    const panel = document.createElement("div");
    panel.hidden = true;
    document.body.append(button, panel);

    let chat;
    button.addEventListener("click", () => {
      chat ??= mount(panel, { inline: false });
      panel.hidden = !panel.hidden;
      button.classList.toggle("kx-chat-fab--open", !panel.hidden);
      if (!panel.hidden) chat.focus();
    });
  }

  const init = () => {
    const page = document.getElementById("kx-chat-page");
    if (page) mount(page, { inline: true }).focus();
    else mountFloating();
  };

  // Material's instant navigation re-runs scripts via document$; fall back to DOMContentLoaded.
  if (window.document$) window.document$.subscribe(() => {
    if (!document.querySelector(".kx-chat, .kx-chat-fab")) init();
  });
  else document.addEventListener("DOMContentLoaded", init);
})();
