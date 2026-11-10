// 문의 양식을 웹툰 서버의 문의 API(POST /api/webtoon/v1/contact)로 바로 보낸다.
// 서버가 lore@lorecomic.com 으로 메일을 보낸다. 랜딩이 lorecomic.com 에 올라가면 상대 경로로 닿는다.
// 다른 주소에서 미리 볼 때는 <form data-api="https://..."> 로 서버 주소를 지정할 수 있다.
(function () {
  var form = document.querySelector("form[data-send]");
  if (!form) return;
  var status = form.querySelector(".status");
  var button = form.querySelector("button[type=submit]");

  function show(kind, text) {
    status.className = "status " + kind;
    status.innerHTML = text;
  }

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    var d = new FormData(form);
    var kind = d.get("kind") || "";
    var lines = [
      "[협업 랜딩 문의]",
      "문의 유형: " + kind,
      "회사·소속: " + (d.get("org") || ""),
      "이름·직함: " + (d.get("name") || ""),
      "",
      (d.get("message") || "").trim()
    ];
    var body = new FormData();
    body.append("email", d.get("email") || "");
    // 서버가 받는 유형은 WEBTOON·ACCOUNT_CREDITS·ERROR·OTHER 넷뿐이라, 협업 문의는 OTHER 로 보내고 본문 첫 줄에 유형을 적는다.
    body.append("category", "OTHER");
    body.append("message", lines.join("\n"));
    body.append("privacyConsent", d.get("privacyConsent") ? "true" : "false");
    body.append("website", d.get("website") || "");

    if (!d.get("privacyConsent")) { show("err", "개인정보 수집·이용에 동의해 주세요."); return; }
    if ((d.get("message") || "").trim().length < 5) { show("err", "문의 내용을 5자 이상 적어 주세요."); return; }

    var base = form.getAttribute("data-api") || "";
    button.disabled = true;
    show("wait", "보내는 중…");
    fetch(base + "/api/webtoon/v1/contact", { method: "POST", body: body })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, j: j }; });
      })
      .then(function (res) {
        if (res.ok) {
          form.reset();
          show("ok", "문의를 보냈습니다. 적어 주신 이메일로 답장드릴게요.");
        } else {
          var msg = (res.j && (res.j.message || (res.j.error && res.j.error.message))) || "보내지 못했어요. 잠시 뒤 다시 시도해 주세요.";
          show("err", msg);
        }
      })
      .catch(function () {
        show("err", "보내지 못했어요. <a href=\"mailto:lore@lorecomic.com\">lore@lorecomic.com</a> 으로 바로 메일을 보내 주세요.");
      })
      .then(function () { button.disabled = false; });
  });
})();
