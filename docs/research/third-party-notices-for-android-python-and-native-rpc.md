# Third-party licence notices for an Android app that bundles Python and a native Rust library

Status: licence texts and pages read 2026-11-16. This is a checklist of what each licence says to preserve or offer, not legal advice, and not an inventory of any specific release. The exact versions bundled in a particular build were not re-checked here, so each build needs its own check against the files it ships.

## What each licence asks for
| Component | Licence | What to carry in a shipped app |
|---|---|---|
| Chaquopy (Python for Android) | MIT, "Copyright (c) 2017-2025 Chaquo Ltd and contributors" | Keep the copyright and permission notice: "shall be included in all copies or substantial portions of the Software." https://raw.githubusercontent.com/chaquo/chaquopy/master/LICENSE.txt . The licensing page says that since version 12.0.1 Chaquopy is free and open source and licence restrictions were removed; older closed-source versions showed a warning and ran 5 minutes at a time. https://chaquo.com/chaquopy/license/ |
| CPython | PSF License Agreement | The agreement and the PSF copyright notice must be "retained in Python ... alone or in any derivative version." https://docs.python.org/3.10/license.html . The page read was for 3.10.20; the licence text per release may change, so use the text that ships with the version in the build. |
| Native chat library (chatmail core, Rust) | Mozilla Public License 2.0 | Section 3.2(a): if you distribute Covered Software in Executable Form, it "must also be made available in Source Code Form" and recipients must be told "how they can obtain a copy of such Source Code Form by reasonable means." Section 3.2(b) allows the executable under other terms as long as they do not limit recipients' rights in the source. https://raw.githubusercontent.com/chatmail/core/main/LICENSE |

## MPL scope, in Mozilla's words
The FAQ calls the MPL "file-level" copyleft and answers Q11: new files that contain no MPL code "are not Modifications, and therefore do not need to be distributed" under the MPL. Distributing a covered executable also requires making its covered source available under section 3.2(a). https://www.mozilla.org/en-US/MPL/2.0/FAQ/

## Conservative packaging approach (a practical suggestion, not a legal minimum)
Not every licence requires a dedicated screen or the full text inside the app's UI; the texts above require the notice to travel with copies, and the MPL requires the source offer. A cautious way to satisfy both is:
- Ship a notices file or screen listing each component, its licence name and its licence text, so the notice travels with the app.
- For the MPL library, give a source location for the exact version in the app (a tag, commit or archive) and say so in the notices. If the library was modified, the changed covered files must be available to recipients of the executable as 3.2(a) describes. Keeping the location available for as long as the app is distributed is a sensible practice; the sources read do not state an exact minimum period.
- CPython's licence page also bundles licence texts for third-party components included in Python, so the PSF text alone is not a complete inventory for a bundled interpreter. Transitive libraries inside a prebuilt native wheel or Python package have their own licences. None of these were enumerated here, so a real inventory has to read the package contents.
- Not verified here: how a particular APK packages these files, or whether a given vendored dependency carries a separate licence.
