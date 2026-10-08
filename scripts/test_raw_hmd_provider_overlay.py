"""Exact allowlist for provider-only overlay changes and unchanged server trust gates."""
import argparse
import io
import subprocess
import tarfile
from pathlib import Path


def require(ok, why):
    if not ok:
        raise RuntimeError(why)


def strip_once(text, addition):
    require(text.count(addition) == 1, 'Provider overlay anchor mismatch: ' + addition[:80])
    return text.replace(addition, '')


def verify_provider(source, output, driver):
    header = (output / 'src/VRDriver.hpp').read_text(encoding='utf-8')
    require(header.index('monaka::RawHmdProviderDriver hmd_provider_;') < header.index('pose_request_thread_') <
            header.index('std::shared_ptr<BridgeClient> bridge_'), 'Provider must outlive workers/bridge callbacks')
    driver = strip_once(driver, '                if (hmd_provider_evidence) hmd_provider_transport_.Attach(*position, *hmd_provider_evidence);\n')
    # No edits to query/send expressions; remove only these reviewed lifecycle additions.
    for addition in (
        '    bridge_->SetProviderLifecycleCallbacks(\n'
        '        [this] { hmd_provider_transport_.Reset(); hmd_provider_.Reestablish(); }, [this] { hmd_provider_transport_.Reset(); hmd_provider_.Retire(); });\n',
        '    hmd_provider_transport_.Reset();\n',
        '    hmd_provider_.Retire();\n',
        '        const auto hmd_provider_session = hmd_provider_.BeginSample();\n',
        '                // Freeze one software observation from this iteration and the exact final send locals.\n'
        '                // No full admission claim; stale lifecycle tickets fail closed without altering sends.\n'
        '                std::optional<monaka::RawHmdProviderEvidenceSnapshot> hmd_provider_evidence;\n'
        '                if (index == vr::k_unTrackedDeviceIndex_Hmd && hmd_provider_session) {\n'
        '                    const auto& raw = *hmd_raw_diagnostic;\n'
        '                    const auto captured = hmd_provider_.Capture(*hmd_provider_session,\n'
        '                        monaka::ProviderHmdPoseSample{\n'
        '                            {raw.px, raw.py, raw.pz, raw.qx, raw.qy, raw.qz, raw.qw},\n'
        '                            {wire_x, wire_y, wire_z, wire_qx, wire_qy, wire_qz, wire_qw},\n'
        '                            static_cast<int32_t>(position->data_source()), pose.bPoseIsValid,\n'
        '                            pose.bDeviceIsConnected, static_cast<int32_t>(pose.eTrackingResult)});\n'
        '                    if (captured) hmd_provider_evidence.emplace(*captured);\n'
        '                }\n',
    ):
        if 'Freeze one' in addition:
            require('position->set_qw(wire_qw);\n' + addition in driver,
                    'One capture after final locals/setters, immediately before existing send branch')
        driver = strip_once(driver, addition)
    for name, additions in {
        'src/bridge/BridgeClient.cpp': (
            '        if (provider_establish_) provider_establish_();\n',
            '    if (provider_retire_) provider_retire_();\n'),
        'src/bridge/BridgeClient.hpp': (
            '    // Set once before Start; independent provider lifecycle, no wire evidence.\n'
            '    void SetProviderLifecycleCallbacks(std::function<void()> establish, std::function<void()> retire) {\n'
            '        provider_establish_ = std::move(establish); provider_retire_ = std::move(retire);\n'
            '    }\n',
            '    std::function<void()> provider_establish_, provider_retire_;\n'),
    }.items():
        overlay = (output / name).read_text(encoding='utf-8')
        if name.endswith('.cpp'):
            require(additions[0] + '        connected_ = true;' in overlay and
                    additions[1] + '    connected_ = false;' in overlay,
                    'Every connect/close must notify synchronously before publishing connection state')
        for addition in additions:
            overlay = strip_once(overlay, addition)
        require(overlay == (source / name).read_text(encoding='utf-8'),
                'Transport change outside exact lifecycle notification allowlist: ' + name)
    pure = (output / 'src/MonakaRawHmdProviderEvidence.hpp').read_text(encoding='utf-8')
    adapter = (output / 'src/MonakaRawHmdProviderDriver.hpp').read_text(encoding='utf-8')
    for forbidden in ('detectedBoundaryGeneration', 'universeId', 'transportSession', 'hostSequence',
                      'sensorTimestamp', 'acquisitionTime', 'bool sourceValid', 'GetRawTrackedDevicePoses',
                      'SearchUniverses', 'VRProperties', 'Logger', 'chrono', 'filesystem'):
        require(forbidden not in pure + adapter, 'Inferred authority/dependency: ' + forbidden)
    require('ProviderAuthorityStatus { Unavailable }' in pure, 'Only Unavailable authority constructible')
    require('BCRYPT_USE_SYSTEM_PREFERRED_RNG' in adapter and 'getrandom(' in adapter,
            'Production token factory must use independent OS entropy')
    require('Capture(const ProviderSessionEpoch& expected,' in pure,
            'Capture must reject samples crossing a lifecycle boundary')
    require('Log(' not in adapter and 'format(' not in adapter and 'getenv(' not in adapter,
            'No provider formatting/log I/O, even with env flags')
    return driver


def verify_server(root):
    # Only the reviewed 5U integration/generation paths may change existing server bytes.
    base = '164a2d86bfdd0ea29fa269e69fa9825c0707e3fb'
    allowed = {
        'server/build.gradle.kts', 'server/desktop/protobuf_update.bat',
        'server/desktop/src/main/java/dev/monaka/tracking/desktop/TrustedRawHmdPositionSource.kt',
        'server/desktop/src/main/java/dev/slimevr/desktop/platform/ProtobufBridge.kt',
        'server/desktop/src/main/java/dev/slimevr/desktop/platform/ProtobufMessages.java',
        'server/desktop/src/test/java/dev/monaka/tracking/desktop/TrustedHmdPositionProvenanceTests.kt',
        'server/desktop/src/test/java/dev/monaka/tracking/desktop/InboundTransportSessionTests.kt',
    }
    archive = subprocess.check_output(['git', '-C', str(root), 'archive', '--format=tar', base, 'server'])
    checked = 0
    with tarfile.open(fileobj=io.BytesIO(archive)) as files:
        for entry in files:
            if not entry.isfile() or entry.name in allowed: continue
            original = files.extractfile(entry).read()
            require((root / entry.name).read_bytes().replace(b'\r\n', b'\n') == original.replace(b'\r\n', b'\n'),
                    'Unrelated server/admission file changed: ' + entry.name)
            checked += 1
    bridge = (root / 'server/desktop/src/main/java/dev/slimevr/desktop/platform/ProtobufBridge.kt').read_text()
    source = (root / 'server/desktop/src/main/java/dev/monaka/tracking/desktop/TrustedRawHmdPositionSource.kt').read_text()
    dto = (root / 'server/desktop/src/main/java/dev/monaka/tracking/desktop/HmdProviderSampleTransport.kt').read_text()
    require('ReviewedHmdBackendContract(' not in bridge + source + dto and
            'establishProviderSession(' not in bridge + dto, 'No production full backend/session registration')
    require('val rawHmdPoseInputCapability: RawHmdPoseInputCapability = RawHmdPoseInputCapability.Unavailable(' in source,
            'Production admission remains unavailable')
    require('sourceIdentity, providerSessionEpoch, observationId, null, null, null, null, null' in dto,
            'Only partial projection; sourceValid and all raw/mapping authority absent')
    require('providerTransportEvidence?.partialProviderEvidence(accepted.ingressIdentity.sourceId)' in source,
            'One-way projection from DTO, server-derived registered source identity')
    print(f'PASS {checked} unrelated server/admission files unchanged; partial-only projection, no full registration')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--overlay', type=Path, required=True)
    args = parser.parse_args()
    verify_provider(args.source, args.overlay, (args.overlay / 'src/VRDriver.cpp').read_text(encoding='utf-8'))
    verify_server(Path(__file__).resolve().parents[1])
    print('PASS independent provider lifecycle/exact final P/Q boundary/Unavailable authority/default zero logging')


if __name__ == '__main__':
    main()
