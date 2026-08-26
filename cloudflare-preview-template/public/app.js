const CATEGORIES = {
  PHONE_APP: {
    label: "Apps",
    caption: "System- und Providerwege",
    items: [
      { id: "share", icon: "↗", title: "System Share Sheet", protocol: "SYSTEM_SHARE_SHEET", state: "READY", summary: "Übergibt Inhalte ausschließlich an die vom Nutzer sichtbare Systemauswahl.", access: "Sichtbare Nutzeraktion", gate2: false, locality: "Local-first", tone: "success" },
      { id: "files", icon: "▱", title: "System File Picker", protocol: "SYSTEM_FILE_PICKER", state: "READY", summary: "Dateien werden nur über den Betriebssystemdialog und nach expliziter Auswahl geöffnet.", access: "Nur ausgewählte Datei", gate2: false, locality: "Local-first", tone: "cyan" },
      { id: "notify", icon: "◉", title: "Benachrichtigungen", protocol: "OS_NOTIFICATION", state: "NEEDS_AUTH", summary: "Lokale Hinweise benötigen Betriebssystemfreigabe; stille Vollzugriffe sind ausgeschlossen.", access: "Governed Write", gate2: true, locality: "Local-first", tone: "warning" },
      { id: "calendar", icon: "□", title: "Kalender", protocol: "PROVIDER_ADAPTER", state: "ADAPTER_REQUIRED", summary: "Lesen und Schreiben bleiben getrennte Fähigkeiten mit eigener Nutzerfreigabe.", access: "Governed Read/Write", gate2: true, locality: "Provider-Adapter", tone: "violet" },
      { id: "mail", icon: "✉", title: "E-Mail", protocol: "PROVIDER_ADAPTER", state: "ADAPTER_REQUIRED", summary: "Entwürfe sind keine Sendungen. Versand benötigt eine neue, sichtbare Bestätigung.", access: "Entwurf / Sendung getrennt", gate2: true, locality: "Provider-Adapter", tone: "rose" },
      { id: "assistant", icon: "✦", title: "LLM Provider", protocol: "CONSENTED_API", state: "NEEDS_AUTH", summary: "Modelle werden über begrenzte Provideradapter angebunden; keine implizite Geräteautorität.", access: "Governed Request", gate2: true, locality: "Netzwerkadapter", tone: "cyan" }
    ]
  },
  MACHINE: {
    label: "Maschinen",
    caption: "Nahbereich, IoT und Industrie",
    items: [
      { id: "ble", icon: "ᛒ", title: "Bluetooth LE", protocol: "BLE_GATT", state: "NEEDS_AUTH", summary: "Pairing, Geräteidentität und Capability-Whitelist werden vor jedem Effekt geprüft.", access: "Governed Read/Write", gate2: true, locality: "Local-first", tone: "cyan" },
      { id: "wifi", icon: "⌁", title: "Wi-Fi Direct", protocol: "WIFI_DIRECT", state: "ADAPTER_REQUIRED", summary: "Direkte Gerätewege benötigen einen signierten Adapter und eine begrenzte Befehlsschnittstelle.", access: "Governed Read/Write", gate2: true, locality: "Local-first", tone: "violet" },
      { id: "matter", icon: "◇", title: "Matter", protocol: "MATTER_RUNTIME", state: "RUNTIME_REQUIRED", summary: "Smart-Home-Fähigkeiten werden pro Gerät und Aktion begrenzt; verbunden heißt nicht autorisiert.", access: "Capability-basiert", gate2: true, locality: "Runtime", tone: "warning" },
      { id: "mqtt", icon: "⇄", title: "MQTT", protocol: "MQTT_TLS", state: "ADAPTER_REQUIRED", summary: "Topics, Payload-Schema und Schreibrechte werden auf eine explizite Allowlist reduziert.", access: "Governed Read/Write", gate2: true, locality: "Edge-Bridge", tone: "cyan" },
      { id: "opc", icon: "⬡", title: "OPC UA", protocol: "OPC_UA_EDGE", state: "ADAPTER_REQUIRED", summary: "Industriekommunikation bleibt hinter einem Edge-Gateway mit Fail-Closed-Befehlsprofil.", access: "Edge-isoliert", gate2: true, locality: "Edge-Bridge", tone: "rose" },
      { id: "modbus", icon: "▥", title: "Modbus", protocol: "MODBUS_EDGE", state: "UNAVAILABLE", summary: "Kein direkter Mobilzugriff. Nur über geprüfte Industriebrücke und freigegebene Register.", access: "Kein Direktzugriff", gate2: true, locality: "Edge-Bridge", tone: "rose" }
    ]
  },
  SPATIAL_OUTPUT: {
    label: "Spatial",
    caption: "XR, Projektion und Holografie",
    items: [
      { id: "webxr", icon: "◌", title: "WebXR", protocol: "WEBXR", state: "READY", summary: "Räumliche Webszenen bleiben in der kompatiblen Browser-Runtime und benötigen Nutzerinteraktion.", access: "Sichtbare Ausgabe", gate2: false, locality: "Browser-Runtime", tone: "success" },
      { id: "openxr", icon: "◎", title: "OpenXR Bridge", protocol: "OPENXR_BRIDGE", state: "RUNTIME_REQUIRED", summary: "Headsets werden über eine begrenzte Runtime gekoppelt; Pose-Daten bleiben zweckgebunden.", access: "Governed Session", gate2: true, locality: "Runtime", tone: "violet" },
      { id: "projection", icon: "▰", title: "Spatial Projection", protocol: "DISPLAY_ADAPTER", state: "ADAPTER_REQUIRED", summary: "Projektionsprofile begrenzen Auflösung, Fläche, Interaktion und zulässige Inhalte.", access: "Governed Output", gate2: true, locality: "Display-Adapter", tone: "cyan" },
      { id: "lightfield", icon: "✧", title: "Light-Field Display", protocol: "VENDOR_RUNTIME", state: "RUNTIME_REQUIRED", summary: "Physische Tiefendarstellung setzt kompatible Hardware und die Hersteller-Runtime voraus.", access: "Governed Output", gate2: true, locality: "Vendor-Runtime", tone: "rose" },
      { id: "hologram", icon: "△", title: "Holographic Surface", protocol: "SPATIAL_SCENE", state: "ADAPTER_REQUIRED", summary: "Die Glasoptik läuft sofort; reale Holografie benötigt eine nachgewiesene Ausgabekette.", access: "Governed Output", gate2: true, locality: "Display-Adapter", tone: "violet" },
      { id: "audio", icon: "≋", title: "Spatial Audio", protocol: "WEB_AUDIO_SPATIAL", state: "READY", summary: "Klangräume sind lokal abschaltbar, respektieren Reduced Motion und starten nie automatisch laut.", access: "Sichtbare Ausgabe", gate2: false, locality: "Local-first", tone: "success" }
    ]
  }
};

const stateLabels = {
  READY: "BEREIT",
  NEEDS_AUTH: "FREIGABE NÖTIG",
  ADAPTER_REQUIRED: "ADAPTER NÖTIG",
  RUNTIME_REQUIRED: "RUNTIME NÖTIG",
  UNAVAILABLE: "NICHT VERFÜGBAR"
};

const stateOrder = ["READY", "NEEDS_AUTH", "ADAPTER_REQUIRED", "RUNTIME_REQUIRED", "UNAVAILABLE"];
let category = "PHONE_APP";
let active = 0;
let rotation = 0;
let zoom = 1;
let halted = false;
let dragging = false;
let dragStart = 0;
let rotationStart = 0;

const orbit = document.querySelector("#orbit");
const detail = document.querySelector("#detail");
const metrics = document.querySelector("#metrics");
const categoryButtons = [...document.querySelectorAll("[data-category]")];
const stopButton = document.querySelector("#stop");
const edgeState = document.querySelector("#edge-state");
const sourceCommit = document.querySelector("#source-commit");

function currentItems() {
  return CATEGORIES[category].items;
}

function normalize(index) {
  const count = currentItems().length;
  return ((index % count) + count) % count;
}

function nearestRelative(index) {
  const count = currentItems().length;
  let relative = index - rotation;
  while (relative > count / 2) relative -= count;
  while (relative < -count / 2) relative += count;
  return relative;
}

function renderMetrics() {
  const items = currentItems();
  const ready = items.filter((item) => item.state === "READY").length;
  const runtime = items.filter((item) => ["ADAPTER_REQUIRED", "RUNTIME_REQUIRED"].includes(item.state)).length;
  const gate2 = items.filter((item) => item.gate2).length;
  metrics.innerHTML = [
    [String(items.length).padStart(2, "0"), "Schnittstellen"],
    [String(ready).padStart(2, "0"), "sofort nutzbar"],
    [String(runtime).padStart(2, "0"), "Adapter / Runtime"],
    [String(gate2).padStart(2, "0"), "Gate 2"]
  ].map(([value, label]) => `<div class="metric glass"><strong>${value}</strong><span>${label}</span></div>`).join("");
}

function renderOrbit() {
  const items = currentItems();
  orbit.innerHTML = items.map((item, index) => {
    const activeClass = index === active ? " active" : "";
    return `<button class="orbit-card glass tone-${item.tone}${activeClass}" data-index="${index}" aria-label="${item.title}, ${stateLabels[item.state]}">
      <span class="card-top"><span class="card-icon">${item.icon}</span><span class="badge">${stateLabels[item.state]}</span></span>
      <strong>${item.title}</strong><em>${item.protocol.replaceAll("_", " ")}</em>
      <span class="card-copy">${item.summary}</span><span class="card-hint">Tippen zum Fokussieren</span>
    </button>`;
  }).join("");
  orbit.querySelectorAll(".orbit-card").forEach((card) => {
    card.addEventListener("click", () => focus(Number(card.dataset.index)));
  });
  positionCards();
}

function positionCards() {
  const cards = [...orbit.querySelectorAll(".orbit-card")];
  const count = cards.length;
  const radius = Math.min(Math.max(window.innerWidth * 0.19, 150), 245);
  cards.forEach((card, index) => {
    const relative = nearestRelative(index);
    const angle = relative * (Math.PI * 2 / Math.max(count, 5));
    const depth = Math.cos(angle);
    const x = Math.sin(angle) * radius;
    const y = (1 - depth) * 46;
    const scale = (0.53 + ((depth + 1) / 2) * 0.5) * zoom;
    const opacity = Math.max(0.08, Math.min(1, (depth + 1.08) / 1.52));
    card.style.transform = `translate3d(${x}px, ${y}px, 0) scale(${scale}) rotateY(${-angle * 24}deg)`;
    card.style.opacity = halted ? "0.18" : String(opacity);
    card.style.zIndex = String(Math.round((depth + 1) * 100));
    card.classList.toggle("active", index === active);
  });
}

function renderDetail() {
  const item = currentItems()[active];
  detail.innerHTML = `<div class="detail-head"><div><span>FOKUS / ${stateLabels[item.state]}</span><h3>${item.title}</h3></div><span class="protocol">${item.protocol}</span></div>
    <p>${item.summary}</p>
    <div class="chips"><span>${item.access}</span><span>${item.gate2 ? "Gate 2 erforderlich" : "Kein Ausführungsgate"}</span><span>${item.locality}</span></div>
    <div class="detail-actions"><button data-action="pair">Freigaben & Pairing</button><button data-action="execute" ${item.state === "READY" && !item.gate2 ? "" : "disabled"}>Preview-Aktion</button></div>`;
  detail.querySelector("[data-action='pair']").addEventListener("click", () => openModal("Freigaben & Pairing", "Diese Vorschau zeigt den Vertrag, führt aber keine Konto-, Geräte- oder Maschinenaktion aus. Berechtigung und Ausführungsfreigabe bleiben getrennt."));
  detail.querySelector("[data-action='execute']").addEventListener("click", () => openModal("Authority-free Preview", "Die sichtbare Interaktion wurde lokal simuliert. Es wurde kein externer Effekt ausgelöst und kein Gerät angesprochen."));
}

function focus(index, animate = true) {
  active = normalize(index);
  rotation = active;
  if (!animate || matchMedia("(prefers-reduced-motion: reduce)").matches) {
    positionCards();
  } else {
    orbit.animate([{ opacity: 0.84 }, { opacity: 1 }], { duration: 220, easing: "ease-out" });
    positionCards();
  }
  renderDetail();
}

function changeCategory(next) {
  category = next;
  active = 0;
  rotation = 0;
  zoom = 1;
  categoryButtons.forEach((button) => button.classList.toggle("active", button.dataset.category === next));
  renderMetrics();
  renderOrbit();
  renderDetail();
  document.querySelector("#category-caption").textContent = CATEGORIES[next].caption;
}

function move(direction) {
  focus(active + direction);
}

function setHalted(next) {
  halted = next;
  document.body.classList.toggle("halted", halted);
  stopButton.classList.toggle("active", halted);
  stopButton.querySelector("span").textContent = halted ? "Preview fortsetzen" : "Emergency Stop";
  positionCards();
  openModal(halted ? "Emergency Stop aktiv" : "Preview fortgesetzt", halted
    ? "Alle simulierten Ausführungspfade sind angehalten. Anzeige und Prüfnachweise bleiben lesbar."
    : "Die rein visuelle Vorschau läuft weiter. Reale Ausführungsautorität bleibt weiterhin null.");
}

function openModal(title, text) {
  const modal = document.querySelector("#modal");
  modal.querySelector("h3").textContent = title;
  modal.querySelector("p").textContent = text;
  modal.classList.add("open");
}

categoryButtons.forEach((button) => button.addEventListener("click", () => changeCategory(button.dataset.category)));
document.querySelector("#prev").addEventListener("click", () => move(-1));
document.querySelector("#next").addEventListener("click", () => move(1));
document.querySelector("#reset").addEventListener("click", () => { zoom = 1; positionCards(); });
stopButton.addEventListener("click", () => setHalted(!halted));
document.querySelector("#modal-close").addEventListener("click", () => document.querySelector("#modal").classList.remove("open"));
document.querySelector("#modal").addEventListener("click", (event) => { if (event.target.id === "modal") event.currentTarget.classList.remove("open"); });

orbit.addEventListener("pointerdown", (event) => {
  dragging = true;
  dragStart = event.clientX;
  rotationStart = rotation;
  orbit.setPointerCapture(event.pointerId);
});
orbit.addEventListener("pointermove", (event) => {
  if (!dragging) return;
  rotation = rotationStart - (event.clientX - dragStart) / 145;
  positionCards();
});
orbit.addEventListener("pointerup", (event) => {
  if (!dragging) return;
  dragging = false;
  orbit.releasePointerCapture(event.pointerId);
  focus(Math.round(rotation));
});
orbit.addEventListener("pointercancel", () => { dragging = false; focus(Math.round(rotation)); });
orbit.addEventListener("wheel", (event) => {
  event.preventDefault();
  if (event.ctrlKey || event.metaKey) {
    zoom = Math.max(0.76, Math.min(1.24, zoom - event.deltaY * 0.001));
    positionCards();
  } else {
    rotation += event.deltaY * 0.0026 + event.deltaX * 0.004;
    focus(Math.round(rotation));
  }
}, { passive: false });
window.addEventListener("resize", positionCards);
window.addEventListener("keydown", (event) => {
  if (event.key === "ArrowLeft") move(-1);
  if (event.key === "ArrowRight") move(1);
  if (event.key === "Escape") document.querySelector("#modal").classList.remove("open");
});

async function readHealth() {
  try {
    const response = await fetch("/healthz", { cache: "no-store" });
    const data = await response.json();
    if (!response.ok || data.status !== "ok" || data.engineCount !== 5 || data.a7semReverseIsEngine !== false) throw new Error("invalid health contract");
    edgeState.textContent = "CLOUDFLARE EDGE OK";
    edgeState.closest(".status-pill").classList.add("ok");
    sourceCommit.textContent = data.sourceCommit.slice(0, 7);
  } catch {
    edgeState.textContent = "LOCAL PREVIEW";
    sourceCommit.textContent = "a79564a";
  }
}

changeCategory(category);
void readHealth();
