#include "monaka/protocol/v2/codec.hpp"
#include <fstream>
#include <iterator>
#include <limits>
#include <stdexcept>
using namespace monaka::protocol::v2;
void check(bool b) { if(!b) throw std::runtime_error("authority model check failed"); }
int main(int argc,char** argv) {
    check(argc==2);
    std::ifstream f(argv[1],std::ios::binary);
    std::string raw((std::istreambuf_iterator<char>(f)),{}), encoded="unchanged";
    Envelope value; Error error;
    check(DecodeEnvelope(reinterpret_cast<const uint8_t*>(raw.data()),raw.size(),value,error));
    auto original=std::get<TrustedHmdCommonPose>(value);
    for(auto x:{std::numeric_limits<double>::quiet_NaN(),std::numeric_limits<double>::infinity()}) {
        auto bad=original; bad.source_position[0]=x;
        check(!EncodeEnvelope(bad,encoded,error) && error.code==ErrorCode::OutOfRange && encoded=="unchanged");
        bad=original; bad.common_position[0]=x;
        check(!EncodeEnvelope(bad,encoded,error) && error.code==ErrorCode::OutOfRange && encoded=="unchanged");
    }
    auto bad=original; bad.source.observation_id=-1;
    check(!EncodeEnvelope(bad,encoded,error) && error.code==ErrorCode::OutOfRange);
    bad=original; bad.common_orientation={0,0,0,2};
    check(!EncodeEnvelope(bad,encoded,error) && error.code==ErrorCode::InvalidQuaternion);
    bad=original; bad.source.source_space.source_space_generation=1;
    check(!EncodeEnvelope(bad,encoded,error) && error.code==ErrorCode::InconsistentValidity);
    bad=original; bad.version.minor=0;
    check(!EncodeEnvelope(bad,encoded,error) && error.code==ErrorCode::UnsupportedVersion);
    auto good=original; good.version.minor=42;
    check(EncodeEnvelope(good,encoded,error));
    check(DecodeEnvelope(reinterpret_cast<const uint8_t*>(encoded.data()),encoded.size(),value,error));
    check(std::get<TrustedHmdCommonPose>(value).version.minor==1);
}
