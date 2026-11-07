// Small touches only: dimmed comments and a copy button in code blocks, and the docs' table of contents.
(function () {
  "use strict";

  // A "#" starts a comment (Nix and shell alike) at the start of a line or after a space, outside "…" strings.
  function commentStart(line) {
    var quoted = false;
    for (var i = 0; i < line.length; i++) {
      var ch = line[i];
      if (ch === "\\" && quoted) { i++; continue; }
      if (ch === '"') quoted = !quoted;
      else if (ch === "#" && !quoted && (i === 0 || /\s/.test(line[i - 1]))) return i;
    }
    return -1;
  }

  function escapeHtml(text) {
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  document.querySelectorAll("pre > code").forEach(function (code) {
    var source = code.textContent;
    code.innerHTML = source.split("\n").map(function (line) {
      var at = commentStart(line);
      if (at < 0) return escapeHtml(line);
      return escapeHtml(line.slice(0, at)) + '<span class="c">' + escapeHtml(line.slice(at)) + "</span>";
    }).join("\n");

    if (!navigator.clipboard) return;
    var button = document.createElement("button");
    button.type = "button";
    button.className = "copy";
    button.textContent = "Copy";
    button.addEventListener("click", function () {
      navigator.clipboard.writeText(source.replace(/\n$/, "")).then(function () {
        button.textContent = "Copied";
        setTimeout(function () { button.textContent = "Copy"; }, 1500);
      });
    });
    code.parentNode.appendChild(button);
  });

  // Docs: the table of contents starts folded on small screens and marks the section being read.
  var toc = document.querySelector(".toc");
  if (!toc) return;
  var details = toc.querySelector("details");
  if (details && window.matchMedia("(max-width: 900px)").matches) details.open = false;
  toc.addEventListener("click", function (event) {
    if (event.target.closest("a") && details && window.matchMedia("(max-width: 900px)").matches) details.open = false;
  });

  var links = Array.prototype.slice.call(toc.querySelectorAll('a[href^="#"]'));
  var targets = links.map(function (a) { return document.getElementById(a.getAttribute("href").slice(1)); });
  if (!("IntersectionObserver" in window)) return;
  var visible = new Set();
  var observer = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) visible.add(entry.target); else visible.delete(entry.target);
    });
    var current = targets.find(function (t) { return visible.has(t); });
    if (!current) return;
    links.forEach(function (a, i) { a.classList.toggle("active", targets[i] === current); });
    // A table of contents taller than the screen scrolls by itself, so the marked section stays in sight.
    var active = links[targets.indexOf(current)];
    if (active && toc.scrollHeight > toc.clientHeight) {
      var box = toc.getBoundingClientRect(), at = active.getBoundingClientRect();
      if (at.top < box.top || at.bottom > box.bottom) toc.scrollTop += at.top - box.top - box.height / 3;
    }
  }, { rootMargin: "-70px 0px -60% 0px" });
  targets.forEach(function (t) { if (t) observer.observe(t); });
})();
