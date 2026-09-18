# mkpro v4.5.0 Release Notes

We are excited to announce the release of **mkpro v4.5.0**! This update brings a major overhaul to the default user interface, introduces powerful new tools for screen capture and file inspection, and includes critical backend performance and security enhancements. 

Below is a detailed breakdown of the new features, improvements, and fixes included in this release.

---

## 🌟 Major Features

### 1. Academic View Overhaul
We have redesigned the primary user experience to prioritize academic and structured workflows.
* **New Default Interface:** The default route (`http://localhost:8080/`) now points directly to the new `academic_view.html`.
* **Classic View Relocation:** The previous default interface has been preserved and moved to the `/classic` endpoint.
* **Seamless Navigation:** Added built-in cross-navigation, allowing users to seamlessly toggle between the Academic and Classic views.

### 2. Modular File Inspector
File preview and inspection have been completely rebuilt for better versatility and deeper integration.
* **Dual-Mode Rendering:** HTML and Markdown files now support a dual-mode viewer, allowing you to easily toggle between **Source** and **Preview** modes.
* **Integrated PDF Viewer:** Replaced basic PDF handling with an integrated PDF.js canvas viewer for high-fidelity, in-browser document reading.
* **Image Lightbox:** Added a lightbox feature with zooming capabilities for detailed image inspection.
* **Binary Fallback:** Unrecognized or unsupported binary files are now gracefully handled via interactive fallback cards.

### 3. Screenshot Tool (`/capture`)
Capturing and sharing visual context is now built directly into your workflow.
* **New Slash Command:** Introducing the `/capture` command for quick screen grabs.
* **Multi-Monitor Support:** Fully supports capturing across multi-monitor setups with robust OS-level fallbacks.
* **Smart Integration:** Captures are output as interactive chat cards and automatically launch the new File Inspector for immediate review.

---

## ⚡ Improvements & Security

### Backend Streaming & Security
* **High-Performance Streaming:** The `RestApiHandler` has been upgraded to utilize true `transferTo()` binary streaming, drastically improving performance and supporting file transfers up to 100MB.
* **Security Whitelisting:** Updated the `PathValidator` to securely allow access to the new `.mkpro/captures/` directory for the screenshot tool.

---

## 🐛 UI & Layout Fixes
* **Grid & Alignment:** Resolved CSS Grid layout issues and implemented sticky positioning to ensure strict alignment for code line numbers during scrolling.
* **Markdown Rendering:** Fixed a bug in `formatMarkdown` where inline images were not rendering correctly, ensuring a flawless visual markdown experience.

---

*Thank you for using mkpro! If you encounter any issues or have feedback, please reach out to the development team.*