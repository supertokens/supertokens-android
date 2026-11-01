"""Strict Gradle JUnit extraction; no network or optional XML dependencies."""
import json
import re
import sys
import xml.etree.ElementTree as ET

FLOWS = ["LOGIN", "REFRESH", "SIGN_OUT", "PAYLOAD_UPDATE"]
CLASS = "com.example.example.PublishedUpgradeLiveTest"


def require(value, message):
    if not value:
        raise ValueError(message)


def parse(xml, summary, mode, route, nonce):
    require("<!DOCTYPE" not in xml.upper() and "<!ENTITY" not in xml.upper(), "XML declarations forbidden")
    suite = ET.fromstring(xml)
    require(suite.tag == "testsuite" and suite.get("name") == CLASS, "Wrong JUnit suite")
    require(all(child.tag in {"properties", "testcase", "system-out", "system-err"} for child in suite), "Unknown JUnit node")
    cases = suite.findall("testcase")
    names = [f"publishedSessionContinuesAgainstConfiguredFdiMember[{mode}-{flow}-{route}]" for flow in FLOWS]
    require(len(cases) == 4 and sorted(case.get("name") for case in cases) == sorted(names), "Incomplete/duplicate cases")
    require(all(case.get("classname") == CLASS for case in cases), "Wrong JUnit class")
    stdout = "\n".join(node.text or "" for node in suite.findall("system-out"))
    segments = re.split(r"^LIVE_UPGRADE node=.*? mode=(\w+) flow=(\w+) login=([^\r\n]+)\r?$", stdout, flags=re.M)
    require(len(segments) == 17, "Missing/duplicate fixture execution segments")
    observed_flows = []
    for index in range(1, len(segments), 4):
        segment_mode, segment_flow, segment_route, body = segments[index:index + 4]
        require(segment_mode == mode and segment_route == route and segment_flow in FLOWS, "Wrong fixture execution")
        observed_flows.append(segment_flow)
        owners = re.findall(r"^LIVE_UPGRADE fixtureOwner=(\S+)\r?$", body, re.M)
        cleaned = re.findall(r"^LIVE_UPGRADE cleanupOwner=(\S+) cleaned=true\r?$", body, re.M)
        require(len(owners) == 1 and cleaned == owners, "Actual JVM fixture cleanup missing/ambiguous")
    require(sorted(observed_flows) == sorted(FLOWS), "Duplicate fixture execution")
    executions = []
    for line in stdout.splitlines():
        if line.startswith("UPGRADE_EXECUTION "):
            executions.append(json.loads(line[len("UPGRADE_EXECUTION "):]))
    require(len(executions) == 4, "Missing/duplicate actual execution observations")
    rows, failures = [], []
    skipped = errors = 0
    for flow, name in zip(FLOWS, names):
        case = next(case for case in cases if case.get("name") == name)
        require(all(child.tag in {"failure", "error", "skipped", "system-out", "system-err"} for child in case), "Unknown case outcome")
        outcomes = [child for child in case if child.tag in {"failure", "error", "skipped"}]
        require(len(outcomes) <= 1, "Ambiguous case outcomes")
        event = [item for item in executions if item.get("flow") == flow]
        require(len(event) == 1 and event[0].get("mode") == mode and event[0].get("nonce") == nonce,
                "Stale/ambiguous execution observation")
        event = event[0]
        require(type(event.get("candidateEntered")) is bool, "Missing observed candidate entry")
        result = "SUCCESS" if not outcomes else {"failure": "FAILURE", "error": "ERROR", "skipped": "SKIPPED"}[outcomes[0].tag]
        rows.append(dict(className=CLASS, name=name, result=result))
        skipped += result == "SKIPPED"
        errors += result == "ERROR"
        if result == "SUCCESS":
            require(event.get("phase") == "candidate" and event["candidateEntered"] is True,
                    "Successful case lacks actual candidate entry")
        if result != "FAILURE":
            continue
        raw = outcomes[0].text or ""
        require(raw.splitlines() and raw.splitlines()[0] == outcomes[0].get("message") and
                raw.startswith((outcomes[0].get("type") or "") + ": "), "Failure XML type/message disagrees with stack")
        require("Suppressed:" not in raw and "... suppressed" not in raw.lower(), "Ambiguous suppressed failure")
        frames = re.findall(r"^\s+at (?:java\.base/)?([\w.$]+)\(", raw, re.M)
        roots = re.findall(r"^(?:Caused by: )?([\w.$]+): ([^\r\n]+)", raw, re.M)
        require(roots, "Missing root exception")
        require(event.get("phase") == "published" and event["candidateEntered"] is False,
                "Candidate/setup failure is not an alternate")
        method = {"REFRESH": "refresh", "SIGN_OUT": "signOut"}.get(flow)
        require(method and roots[-1] == ("java.lang.IllegalArgumentException", "Empty cookie header string"), "Unexpected failure")
        require("com.supertokens.session.Legacy054Driver." + method in frames and
                "com.supertokens.session.Legacy054Harness.invoke" in frames and
                "com.supertokens.testing.PublishedUpgradeScenario.run" in frames and
                "java.net.HttpCookie.parseInternal" in frames and
                "com.supertokens.session.SuperTokensHttpURLConnection.manuallySetCookiesFromResponse" in frames,
                "Failure lacks published driver/parser stack")
        failures.append(dict(identity=CLASS + "." + name, phase=event["phase"], candidateInvoked=event["candidateEntered"],
                             rootType=roots[-1][0], rootMessage=roots[-1][1], frames=frames, raw=raw))
    counts = dict(tests=len(rows), passed=sum(row["result"] == "SUCCESS" for row in rows), failed=len(failures), skipped=skipped, errors=errors)
    for field, xml_field in [("tests", "tests"), ("failed", "failures"), ("skipped", "skipped"), ("errors", "errors")]:
        require(int(suite.get(xml_field, "-1")) == counts[field], "JUnit totals mismatch")
    for field in ["tests", "passed", "failed", "skipped"]:
        require(summary.get(field) == counts[field], "JSON/XML totals disagree")
    require(sorted(summary.get("cases", []), key=lambda row: row["name"]) == sorted(rows, key=lambda row: row["name"]), "JSON/XML case outcomes disagree")
    require(not skipped and not errors, "Skipped/error JVM cases")
    require(summary.get("rerun") is True and summary.get("excludes") == [] and summary.get("excludedTasks") == [], "Excluded/cached JVM task")
    # A complete, exact suite establishes no missing/aborted cases; absent/partial suites fail above.
    report = dict(summary, **counts, aborted=0, excluded=[])
    return dict(report=report, failures=failures)


if __name__ == "__main__":
    value = json.load(sys.stdin)
    print(json.dumps(parse(**value)))
