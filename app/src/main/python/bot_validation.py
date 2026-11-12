"""scratch-0.2 on-device validation probe (NO account, NO network).

Extends scratch-0.1's runtime probe (core starts, sqlite roundtrip,
foreground service) with a full offline run of the reviewed v6.1 delivery
bot gate under the phone's Chaquopy CPython:

  1. v6.1 gate: DeliveryBot over an on-device OpsStore (stdlib sqlite3),
     seeded history, restore plan emits a real restore envelope.
  2. Digest: all 11 cross-language fixtures match canonical_digest
     byte-for-byte (catches json/sha256/unicode divergence on-device).
  3. Packer: pack_list_identity packs list.json into a bundled sample
     .xdc and the packed bytes verify.
  4. Envelope: marker/list_id/digest/op count match the gate contract.

Everything is local: no account is created and no network is touched.
"""
import json
import os
import sys
import time
import traceback

TEST_LIST = "list-SCRATCH-TEST-01"  # throwaway probe binding, never a real list
OWNER = "owner@scratch.test"
BOT = "bot@scratch.test"


def _gate_probe(files_dir, L):
    from ops_store import OpsStore, RESTORE_MARKER
    from delivery_bot import DeliveryBot, pack_list_identity
    canonical_digest = DeliveryBot.canonical_digest

    clock_t = [1000.0]
    store = OpsStore(os.path.join(files_dir, "ops-v61.db"))
    gate = DeliveryBot(None, store, OWNER, {1}, TEST_LIST, BOT,
                       now=lambda: clock_t[0])
    inst = 4242
    gate._register_trusted(1, inst, "0.4.2", "bot", BOT)
    ops = [
        {"item": "i-1", "field": "title", "value": "v1",
         "stamp": {"hlc": 1, "device": "phone-1"}},
        {"item": "i-1", "field": "done", "value": True,
         "stamp": {"hlc": 3, "device": "laptop-1"}},
        {"item": "i-2", "field": "note", "value": None,
         "stamp": {"hlc": 6, "device": "phone-1"}},
    ]
    store.apply_updates(inst, [{"serial": i + 1, "payload": {"ops": [o]},
                                "info": ""} for i, o in enumerate(ops)])
    sent = []
    n = gate.restore_into(inst, lambda mid, p, i: sent.append(p))
    if n != len(ops) or len(sent) != 1:
        return "restore plan wrong: n=%s envelopes=%s" % (n, len(sent))
    env = sent[0]
    if env.get(RESTORE_MARKER) is not True or env.get("list_id") != TEST_LIST:
        return "envelope identity wrong"
    if env.get("digest") != canonical_digest(env["ops"]) or len(env["ops"]) != 3:
        return "envelope digest/ops wrong"
    L("v6.1 gate: restore envelope ok (%d ops, marker+list+digest)" % n)

    fx_path = os.path.join(os.path.dirname(__file__), "digest-fixtures.json")
    with open(fx_path) as f:
        fixtures = json.load(f)
    bad = [name for name, c in fixtures.items()
           if canonical_digest(c["ops"]) != c["python_digest"]]
    if bad:
        return "digest fixture mismatch: %s" % bad
    L("digest: %d/%d fixtures byte-identical" % (len(fixtures), len(fixtures)))

    import zipfile
    sample = os.path.join(files_dir, "sample-unbound.xdc")
    with zipfile.ZipFile(sample, "w") as z:
        z.writestr("manifest.toml", 'name = "probe"\nversion = "0.0.1"\n')
        z.writestr("index.html", "<html></html>")
    os.makedirs(os.path.join(files_dir, "packed"), exist_ok=True)
    packed = pack_list_identity(sample, TEST_LIST,
                                os.path.join(files_dir, "packed"))
    with zipfile.ZipFile(packed) as z:
        pin = json.loads(z.read("list.json").decode())
    if pin != {"list_id": TEST_LIST}:
        return "packed list.json wrong: %r" % pin
    try:
        pack_list_identity(packed, "list-OTHER", os.path.join(files_dir, "p2"))
        return "packer failed to refuse a conflicting pin"
    except ValueError:
        pass
    L("packer: list.json packed+verified; conflicting pin refused")

    # restart durability on-device
    gate2 = DeliveryBot(None, OpsStore(os.path.join(files_dir, "ops-v61.db")),
                        OWNER, {1}, TEST_LIST, BOT)
    if not gate2.instance_trusted(inst):
        return "restart durability failed"
    L("gate state durable across restart")
    return None


def run(native_dir, files_dir):
    os.environ["OPS_STORE_FORCE_SQLITE"] = "1"
    with open(os.path.join(files_dir, "validation.log"), "w") as log:
        def L(m):
            log.write(time.strftime("%H:%M:%S ") + str(m) + "\n")
            log.flush()
        try:
            L("python " + sys.version.split()[0] + " (chaquopy in-app)")
            server = os.path.join(native_dir, "librpcsrv.so")
            L("bundled server " + server + " exists=" + str(os.path.exists(server)))
            if os.path.exists(server):
                L("mode=%o" % os.stat(server).st_mode)
            from deltachat_rpc_client import Rpc
            rpc = Rpc(accounts_dir=os.path.join(files_dir, "accounts"),
                      rpc_server_path=server)
            rpc.start()
            info = rpc.get_system_info()
            L("core alive: " + json.dumps({k: str(v) for k, v in dict(info).items()})[:400])
            from ops_store import OpsStore
            st = OpsStore(os.path.join(files_dir, "ops.db"))
            fake = [{"serial": 1,
                     "payload": {"ops": [{"item": "i1", "field": "title",
                                          "value": "hello",
                                          "stamp": {"hlc": 1, "device": "scratch"}}]},
                     "info": None}]
            n = st.apply_updates(9001, fake)
            dup = st.apply_updates(9001, fake)
            rows = st.ops_since(0)
            ok = n == 1 and dup == 0 and len(rows) == 1
            L("sqlite roundtrip: new=%d dup=%d rows=%d" % (n, dup, len(rows)))
            err = _gate_probe(files_dir, L)
            if err:
                ok = False
                L("gate probe: " + err)
            import threading
            done = []
            def _close():
                try:
                    rpc.close()
                    done.append(1)
                except Exception:
                    done.append(0)
            t = threading.Thread(target=_close, daemon=True)
            t.start()
            t.join(5)
            L("rpc closed cleanly" if done and done[0] else
              "rpc close skipped/timed out (non-fatal)")
            L("VALIDATION PASS" if ok else "VALIDATION FAIL")
        except Exception:
            L("VALIDATION FAIL")
            L(traceback.format_exc())
