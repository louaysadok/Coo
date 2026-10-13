// Small progressive enhancements. The app works without this file.
(function () {
  // Auto-submit the favourites dropdown when it changes.
  var kind = document.getElementById("fav-kind");
  if (kind) {
    kind.addEventListener("change", function () {
      document.getElementById("fav-form").submit();
    });
  }

  // Confirm destructive actions marked with data-confirm.
  document.querySelectorAll("form[data-confirm]").forEach(function (form) {
    form.addEventListener("submit", function (e) {
      if (!window.confirm(form.dataset.confirm)) e.preventDefault();
    });
  });
})();
