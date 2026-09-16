#include "monaka/protocol/v2/codec.hpp"
#include <chrono>
#include <ctime>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <new>
#include <stdexcept>
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#endif
using namespace monaka::protocol::v2;
static std::size_t allocations=0, allocatedBytes=0;
void* operator new(std::size_t n) { ++allocations; allocatedBytes+=n; if(void* p=std::malloc(n?n:1)) return p; throw std::bad_alloc(); }
void* operator new[](std::size_t n) { return ::operator new(n); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p,std::size_t) noexcept { std::free(p); }
void operator delete[](void* p,std::size_t) noexcept { std::free(p); }
void check(bool x) { if(!x) throw std::runtime_error("self-test failed"); }
double cpuTimeNs() {
#ifdef _WIN32
    FILETIME create,exit,kernel,user;
    check(GetProcessTimes(GetCurrentProcess(),&create,&exit,&kernel,&user)!=0);
    ULARGE_INTEGER k,u;
    k.LowPart=kernel.dwLowDateTime; k.HighPart=kernel.dwHighDateTime;
    u.LowPart=user.dwLowDateTime; u.HighPart=user.dwHighDateTime;
    return double(k.QuadPart+u.QuadPart)*100.0;
#else
    return double(std::clock())*1e9/CLOCKS_PER_SEC;
#endif
}
int main(int argc,char** argv) {
    if(argc==2 && std::string(argv[1])=="--self-test") {
        Envelope out=MtpTrackerState{}; Error e;
        check(!DecodeEnvelope(nullptr,0,out,e) && std::holds_alternative<MtpTrackerState>(out));
        std::string encoded="unchanged";
        auto v=TrackerObservation{}; v.position=Vec3{std::numeric_limits<double>::infinity(),0,0};
        check(!EncodeEnvelope(v,encoded,e) && e.code==ErrorCode::OutOfRange && encoded=="unchanged");
        v.position=Vec3{std::numeric_limits<double>::quiet_NaN(),0,0};
        check(!EncodeEnvelope(v,encoded,e) && e.code==ErrorCode::OutOfRange && encoded=="unchanged");
        std::cout<<"PASS C++ atomic decode/encode and nonfinite model checks\n"; return 0;
    }
    if(argc<2) return 2;
    std::ifstream f(argv[1],std::ios::binary);
    if(!f) return 2;
    std::string bytes((std::istreambuf_iterator<char>(f)),{});
    MtpTrackerState sentinel; sentinel.source_id="unchanged sentinel";
    Envelope out=sentinel; Error e;
    if(!DecodeEnvelope(reinterpret_cast<const uint8_t*>(bytes.data()),bytes.size(),out,e)) {
        check(std::holds_alternative<MtpTrackerState>(out) && std::get<MtpTrackerState>(out).source_id==sentinel.source_id);
        std::cout<<"ERROR:"<<ErrorCodeName(e.code)<<"\n"; return 0;
    }
    if(argc==3 && std::string(argv[2])=="--version-minor") {
        std::cout<<std::visit([](const auto& v) { return v.version.minor; },out)<<"\n"; return 0;
    }
    std::string encoded;
    if(!EncodeEnvelope(out,encoded,e)) { std::cout<<"ERROR:"<<ErrorCodeName(e.code)<<"\n"; return 0; }
    if(argc==3 && std::string(argv[2])=="--bench") {
        constexpr int count=1000;
        for(int i=0;i<100;i++) { DecodeEnvelope(reinterpret_cast<const uint8_t*>(bytes.data()),bytes.size(),out,e); EncodeEnvelope(out,encoded,e); }
        for(bool encode:{false,true}) {
            allocations=0; allocatedBytes=0;
            auto start=std::chrono::steady_clock::now();
            auto cpuStart=cpuTimeNs();
            for(int i=0;i<count;i++) check(encode ? EncodeEnvelope(out,encoded,e) : DecodeEnvelope(reinterpret_cast<const uint8_t*>(bytes.data()),bytes.size(),out,e));
            double ns=std::chrono::duration<double,std::nano>(std::chrono::steady_clock::now()-start).count()/count;
            double cpuNs=(cpuTimeNs()-cpuStart)/count;
            auto a=allocations,b=allocatedBytes;
            std::cout<<(encode?"encode":"decode")<<" ns/op="<<ns<<" process_cpu_ns/op="<<cpuNs<<" allocations/op="<<double(a)/count<<" allocated_bytes/op="<<double(b)/count<<" payload_bytes="<<encoded.size()<<"\n";
        }
    } else std::cout<<encoded<<"\n";
}
