"""Generate schema and typed bindings. Run --check to reject generated drift."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECK = argparse.ArgumentParser()
CHECK.add_argument('--check', action='store_true')
checking = CHECK.parse_args().check

def put(name, content):
    path = ROOT / name
    if checking:
        assert path.read_text(encoding='utf-8') == content, f'generated drift: {name}'
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8', newline='\n')

task = (ROOT / 'docs/codex/Task1_MonakaProtocol.md').read_text(encoding='utf-8')
c1 = task.split('<!-- BEGIN COMMON C1 -->\n')[1].split('<!-- END COMMON C1 -->')[0]
assert hashlib.sha256(c1.encode()).hexdigest() == '3a76435c8b25b3975028f9a83c4dc3d1e3848806c9608d885f5bba74a1198ed3'
put('docs/C1.md', c1)

def enum(*values): return {'type': 'string', 'enum': list(values)}
def ref(name): return {'$ref': '#/$defs/' + name}
def nullable(value): return {'anyOf': [value, {'type': 'null'}]}
def obj(fields):
    return {'type': 'object', 'properties': {k.rstrip('?'): v for k, v in fields.items()},
            'required': [k for k in fields if not k.endswith('?')], 'additionalProperties': True}

defs = {
    'Id': {'type':'string', 'minLength':1, 'maxLength':96, 'pattern':r'^[^\u0000-\u001f\u007f-\u009f]+$'},
    'Uuid': {'type':'string','pattern':r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'},
    'U63': {'type':'string','pattern':r'^(0|[1-9][0-9]*)$', 'maxLength':19},
    'UInt32': {'type':'integer','minimum':0,'maximum':4294967295},
    'UInt16': {'type':'integer','minimum':0,'maximum':65535},
    'Fraction': {'type':'number','minimum':0,'maximum':1},
    'Vec3': {'type':'array','items':{'type':'number'},'minItems':3,'maxItems':3},
    'QuatXyzw': {'type':'array','items':{'type':'number'},'minItems':4,'maxItems':4},
    'TrackingState': enum('initializing','tracked','degraded','lost','disconnected','unknown'),
    'Capability': enum('position','orientation','linear_velocity','angular_velocity','linear_acceleration','battery_fraction','charging'),
    'Presence': enum('present','absent','unknown'),
    'Evidence': enum('device','sample_sanity','none'),
    'TimestampKind': enum('sample','receive'),
    'Frame': enum('space','device'),
    'DerivativeEvidence': enum('measured','derived'),
}
# Field descriptor: name, type, nullable, optional. Public types do not expose JSON.
models = {}
def model(name, fields):
    models[name] = fields
    defs[name] = obj({n + ('?' if optional else ''): nullable(ref(t)) if null else ref(t)
                      for n,t,null,optional in fields})
def fields(**items):
    return [(n, t.rstrip('?'), t.endswith('?'), False) for n,t in items.items()]
defs['Bool'] = {'type':'boolean'}
model('Version', fields(major='UInt16',minor='UInt16'))
defs['Version']['properties']['major'] = {'type':'integer','const':1}
model('CoordinateSpace', fields(id='Id',convention='Id',revision='UInt32'))
model('Battery', fields(fraction='Fraction?',charging='Bool?',timestamp_ns='U63'))
model('Derivative', fields(value='Vec3',frame='Frame',evidence='DerivativeEvidence'))
model('Validity', fields(position='Bool',orientation='Bool'))
model('Confidence', fields(position='Fraction',orientation='Fraction'))
model('Input', fields(device_id='Id',session_id='Uuid',sequence='U63'))
defs['Capabilities'] = {'type':'array','items':ref('Capability'),'uniqueItems':True}
header = fields(version='Version',source_id='Id',session_id='Uuid',clock_id='Uuid',sequence='U63',timestamp_ns='U63',sent_at_ns='U63',timestamp_kind='TimestampKind')
pose = fields(position='Vec3?',orientation='QuatXyzw?',validity='Validity')
common = fields(tracking_state='TrackingState',coordinate_space='CoordinateSpace',capabilities='Capabilities')
messages = {'TrackerObservation':('monaka.observation','pose'), 'ObservationDeviceState':('monaka.observation','device_state'), 'MtpPose':('monaka.tracking','pose'), 'MtpTrackerState':('monaka.tracking','tracker_state')}
for name,(protocol,kind) in messages.items():
    mtp = protocol == 'monaka.tracking'
    f = header + fields(**{'tracker_id' if mtp else 'device_id':'Id'})
    if kind == 'pose':
        f += pose
        if not mtp: f += fields(orientation_evidence='Evidence')
        f += [(n,'Vec3' if mtp else 'Derivative',True,True) for n in ('linear_velocity','angular_velocity','linear_acceleration')]
        if mtp: f += fields(confidence='Confidence')
    else: f += fields(presence='Presence')
    f += common
    if not mtp or kind != 'pose': f += fields(battery='Battery?')
    if mtp: f += fields(mapping_revision='UInt32')
    if mtp and kind == 'pose': f += fields(input='Input')
    model(name,f)
    for k,v in [('protocol',protocol),('type',kind)]:
        defs[name]['properties'][k] = {'type':'string','const':v}
        defs[name]['required'].append(k)
    if mtp:
        defs[name]['properties']['coordinate_space'] = obj({'id':ref('Id'),'convention':{'type':'string','const':'rh_y_up_neg_z_forward'},'revision':ref('UInt32')})

for file,names in [('tracker-observation',list(messages)[:2]),('monaka-tracking',list(messages)[2:])]:
    schema = {'$schema':'https://json-schema.org/draft/2020-12/schema', '$id':f'https://monaka.dev/schema/v1/{file}.schema.json',
              '$comment':'C1 candidate SHA256 ' + hashlib.sha256(c1.encode()).hexdigest(),
              'oneOf':[ref(n) for n in names], '$defs':defs}
    put(f'schema/{file}.schema.json',json.dumps(schema,indent=2,ensure_ascii=False)+'\n')

cpp_types = {'Id':'std::string','Uuid':'std::string','U63':'std::int64_t','UInt32':'std::uint32_t','UInt16':'std::uint16_t','Fraction':'double','Bool':'bool','Vec3':'Vec3','QuatXyzw':'QuatXyzw','Capabilities':'std::vector<std::string>'}
kt_types = {'Id':'String','Uuid':'String','U63':'Long','UInt32':'Long','UInt16':'Int','Fraction':'Double','Bool':'Boolean','Vec3':'List<Double>','QuatXyzw':'List<Double>','Capabilities':'List<String>'}
for n,d in defs.items():
    if 'enum' in d: cpp_types[n]='std::string'; kt_types[n]='String'
cpp = '#pragma once\n#include <array>\n#include <cstdint>\n#include <optional>\n#include <string>\n#include <variant>\n#include <vector>\nnamespace monaka::protocol::v1 {\nusing Vec3 = std::array<double,3>;\nusing QuatXyzw = std::array<double,4>;\n'
kt = 'package dev.monaka.protocol.v1\n\nsealed interface Envelope\n'
conversions = ''
ktcon = 'package dev.monaka.protocol.v1\nimport com.google.gson.*\n\n'
for name,ff in models.items():
    cpp += f'struct {name} {{\n'
    kt += f'data class {name}(\n'
    for n,t,null,opt in ff:
        ct = cpp_types.get(t,t)
        cpp += f'    {"std::optional<"+ct+">" if null else ct} {n}{{}};\n'
        kt += f'    val {n}: {kt_types.get(t,t)}{"?" if null else ""}{" = null" if opt else ""},\n'
    cpp += '};\n'
    kt += ')' + (' : Envelope' if name in messages else '') + '\n\n'
    conversions += f'void to_json(json& j, const {name}& v) {{ j=json::object();\n'
    ktcon += f'internal fun {name}.toJson(): JsonObject = JsonObject().also {{ j ->\n'
    for n,t,null,opt in ff:
        expr = f'std::to_string(v.{n})' if t=='U63' else f'v.{n}'
        if null:
            expr = f'std::to_string(*v.{n})' if t=='U63' else f'*v.{n}'
            conversions += f'    if(v.{n}) j["{n}"]={expr}; else j["{n}"]=nullptr;\n'
        else: conversions += f'    j["{n}"]={expr};\n'
        kexpr = f'{n}.toString()' if t=='U63' else n
        if t in models: kexpr=f'{n}{"?" if null else ""}.toJson()'
        else: kexpr=f'gson.toJsonTree({kexpr})'
        ktcon += f'    j.add("{n}", {kexpr})\n'
    if name in messages:
        p,k=messages[name]
        conversions+=f'    j["protocol"]="{p}"; j["type"]="{k}";\n'
        ktcon+=f'    j.addProperty("protocol", "{p}"); j.addProperty("type", "{k}")\n'
    conversions += '}\n'
    ktcon += '}\n'
    conversions += f'void from_json(const json& j, {name}& v) {{\n'
    ktcon += f'internal fun read{name}(j: JsonObject): {name} = {name}(\n'
    for n,t,null,opt in ff:
        ct=cpp_types.get(t,t)
        expr=f'std::stoll(j.at("{n}").get<std::string>())' if t=='U63' else f'j.at("{n}").get<{ct}>()'
        if null: conversions += f'    if(j.contains("{n}") && !j.at("{n}").is_null()) v.{n}={expr}; else v.{n}.reset();\n'
        else: conversions += f'    v.{n}={expr};\n'
        x=f'j.get("{n}")'
        if t in models: kexpr=f'read{t}({x}.asJsonObject)'
        elif t in ('Vec3','QuatXyzw'): kexpr=f'{x}.asJsonArray.map {{ it.asDouble }}'
        elif t=='Capabilities': kexpr=f'{x}.asJsonArray.map {{ it.asString }}'
        else: kexpr=f'{x}.as'+{'String':'String','Long':'Long','Int':'Int','Double':'Double','Boolean':'Boolean'}[kt_types[t]]
        if null: kexpr=f'if (!j.has("{n}") || {x}.isJsonNull) null else {kexpr}'
        ktcon+=f'    {n} = {kexpr},\n'
    conversions+='}\n'
    ktcon+=')\n'
cpp += 'using Envelope = std::variant<TrackerObservation,ObservationDeviceState,MtpPose,MtpTrackerState>;\n}\n'
put('cpp/include/monaka/protocol/v1/models.hpp',cpp)
put('cpp/src/models_json.inc',conversions)
put('jvm/src/main/kotlin/dev/monaka/protocol/v1/Models.kt',kt)
put('jvm/src/main/kotlin/dev/monaka/protocol/v1/ModelsJson.kt',ktcon)
compact=json.dumps({'$defs':defs},separators=(',',':'))
put('cpp/src/schema.inc', 'static const json schema = json::parse(R"SCHEMA('+compact+')SCHEMA");\n')
put('jvm/src/main/kotlin/dev/monaka/protocol/v1/Schema.kt','package dev.monaka.protocol.v1\nimport com.google.gson.JsonParser\ninternal val schema = JsonParser.parseString("""'+compact.replace('$',"${'$'}")+'""").asJsonObject\n')
