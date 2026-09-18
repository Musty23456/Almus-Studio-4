#include "wav_file.h"

#include <cstdio>
#include <cstring>
#include <algorithm>

namespace almus {

namespace {

struct RiffChunkHeader {
    char id[4];
    uint32_t size;
};

int16_t readLE16(const uint8_t* p) { return static_cast<int16_t>(p[0] | (p[1] << 8)); }
uint32_t readLE32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

void writeLE16(FILE* f, int16_t v) { uint8_t b[2] = {static_cast<uint8_t>(v & 0xFF), static_cast<uint8_t>((v >> 8) & 0xFF)}; fwrite(b, 1, 2, f); }
void writeLE32(FILE* f, uint32_t v) {
    uint8_t b[4] = {
        static_cast<uint8_t>(v & 0xFF), static_cast<uint8_t>((v >> 8) & 0xFF),
        static_cast<uint8_t>((v >> 16) & 0xFF), static_cast<uint8_t>((v >> 24) & 0xFF)
    };
    fwrite(b, 1, 4, f);
}

} // namespace

WavLoadResult loadWavAsStereoFloat(const std::string& path) {
    WavLoadResult result;
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return result;

    char riff[4];
    if (fread(riff, 1, 4, f) != 4 || strncmp(riff, "RIFF", 4) != 0) { fclose(f); return result; }
    fseek(f, 4, SEEK_CUR); // overall size, unused
    char wave[4];
    if (fread(wave, 1, 4, f) != 4 || strncmp(wave, "WAVE", 4) != 0) { fclose(f); return result; }

    uint16_t formatTag = 0, channels = 0, bitsPerSample = 0;
    uint32_t sampleRate = 0;
    std::vector<uint8_t> dataBytes;
    bool haveFmt = false;

    uint8_t chunkHeader[8];
    while (fread(chunkHeader, 1, 8, f) == 8) {
        char id[5] = {0};
        memcpy(id, chunkHeader, 4);
        uint32_t size = readLE32(chunkHeader + 4);

        if (strncmp(id, "fmt ", 4) == 0) {
            std::vector<uint8_t> fmt(size);
            if (fread(fmt.data(), 1, size, f) != size) break;
            formatTag = static_cast<uint16_t>(readLE16(fmt.data() + 0));
            channels = static_cast<uint16_t>(readLE16(fmt.data() + 2));
            sampleRate = readLE32(fmt.data() + 4);
            bitsPerSample = static_cast<uint16_t>(readLE16(fmt.data() + 14));
            haveFmt = true;
        } else if (strncmp(id, "data", 4) == 0) {
            dataBytes.resize(size);
            if (fread(dataBytes.data(), 1, size, f) != size) break;
        } else {
            fseek(f, static_cast<long>(size), SEEK_CUR);
        }
        if (size % 2 == 1) fseek(f, 1, SEEK_CUR); // chunks are word-aligned
    }
    fclose(f);

    if (!haveFmt || dataBytes.empty() || channels == 0) return result;

    const int64_t bytesPerSample = bitsPerSample / 8;
    const int64_t frameCount = static_cast<int64_t>(dataBytes.size()) / (bytesPerSample * channels);

    result.interleavedStereo.resize(static_cast<size_t>(frameCount) * 2);

    auto sampleToFloat = [&](const uint8_t* p) -> float {
        if (formatTag == 3 && bitsPerSample == 32) { // IEEE float
            float v;
            memcpy(&v, p, 4);
            return v;
        }
        if (bitsPerSample == 16) {
            int16_t v = readLE16(p);
            return static_cast<float>(v) / 32768.0f;
        }
        if (bitsPerSample == 24) {
            int32_t v = (p[0]) | (p[1] << 8) | (p[2] << 16);
            if (v & 0x800000) v |= ~0xFFFFFF; // sign extend
            return static_cast<float>(v) / 8388608.0f;
        }
        if (bitsPerSample == 32) { // PCM32
            int32_t v = static_cast<int32_t>(readLE32(p));
            return static_cast<float>(v) / 2147483648.0f;
        }
        return 0.0f;
    };

    const uint8_t* base = dataBytes.data();
    for (int64_t i = 0; i < frameCount; i++) {
        const uint8_t* framePtr = base + i * bytesPerSample * channels;
        float left, right;
        if (channels == 1) {
            float mono = sampleToFloat(framePtr);
            left = right = mono;
        } else {
            left = sampleToFloat(framePtr);
            right = sampleToFloat(framePtr + bytesPerSample);
        }
        result.interleavedStereo[static_cast<size_t>(i) * 2] = left;
        result.interleavedStereo[static_cast<size_t>(i) * 2 + 1] = right;
    }

    result.success = true;
    result.sourceSampleRate = static_cast<int>(sampleRate);
    result.sourceChannels = channels;
    result.frameCount = frameCount;
    return result;
}

bool WavWriter::open(const std::string& path, int sampleRate, int channelCount) {
    file_ = fopen(path.c_str(), "wb");
    if (!file_) return false;
    sampleRate_ = sampleRate;
    channelCount_ = channelCount;
    framesWritten_ = 0;
    writeHeaderPlaceholder();
    return true;
}

void WavWriter::writeHeaderPlaceholder() {
    // WAVE_FORMAT_IEEE_FLOAT (3), 32-bit, written now with zero sizes and
    // patched on close() once the real length is known.
    fwrite("RIFF", 1, 4, file_);
    writeLE32(file_, 0);
    fwrite("WAVE", 1, 4, file_);

    fwrite("fmt ", 1, 4, file_);
    writeLE32(file_, 16);
    writeLE16(file_, 3); // IEEE float
    writeLE16(file_, static_cast<int16_t>(channelCount_));
    writeLE32(file_, static_cast<uint32_t>(sampleRate_));
    writeLE32(file_, static_cast<uint32_t>(sampleRate_ * channelCount_ * 4));
    writeLE16(file_, static_cast<int16_t>(channelCount_ * 4));
    writeLE16(file_, 32);

    fwrite("data", 1, 4, file_);
    writeLE32(file_, 0);
}

void WavWriter::writeFrames(const float* interleaved, int64_t frameCount) {
    if (!file_) return;
    fwrite(interleaved, sizeof(float), static_cast<size_t>(frameCount) * channelCount_, file_);
    framesWritten_ += frameCount;
}

void WavWriter::patchHeaderWithFinalSizes() {
    if (!file_) return;
    uint32_t dataBytes = static_cast<uint32_t>(framesWritten_ * channelCount_ * 4);
    uint32_t riffSize = 36 + dataBytes;
    fseek(file_, 4, SEEK_SET);
    writeLE32(file_, riffSize);
    fseek(file_, 40, SEEK_SET);
    writeLE32(file_, dataBytes);
    fseek(file_, 0, SEEK_END);
}

void WavWriter::close() {
    if (!file_) return;
    patchHeaderWithFinalSizes();
    fclose(file_);
    file_ = nullptr;
}

WavWriter::~WavWriter() { close(); }

bool writeWavFloat(const std::string& path, const float* interleaved,
                    int64_t frameCount, int sampleRate, int channelCount) {
    WavWriter writer;
    if (!writer.open(path, sampleRate, channelCount)) return false;
    writer.writeFrames(interleaved, frameCount);
    writer.close();
    return true;
}

std::vector<float> resampleStereoLinear(const std::vector<float>& srcInterleavedStereo,
                                         int64_t srcFrameCount, int srcSampleRate,
                                         int dstSampleRate, int64_t* outFrameCount) {
    if (srcSampleRate <= 0 || dstSampleRate <= 0 || srcFrameCount <= 0) {
        *outFrameCount = srcFrameCount;
        return srcInterleavedStereo;
    }
    if (srcSampleRate == dstSampleRate) {
        *outFrameCount = srcFrameCount;
        return srcInterleavedStereo;
    }

    const double ratio = static_cast<double>(dstSampleRate) / static_cast<double>(srcSampleRate);
    const int64_t dstFrameCount = static_cast<int64_t>(static_cast<double>(srcFrameCount) * ratio);
    std::vector<float> out(static_cast<size_t>(dstFrameCount) * 2, 0.0f);

    for (int64_t i = 0; i < dstFrameCount; i++) {
        double srcPos = static_cast<double>(i) / ratio;
        int64_t srcIndex = static_cast<int64_t>(srcPos);
        double frac = srcPos - static_cast<double>(srcIndex);
        int64_t nextIndex = std::min(srcIndex + 1, srcFrameCount - 1);

        for (int ch = 0; ch < 2; ch++) {
            float a = srcInterleavedStereo[static_cast<size_t>(srcIndex) * 2 + ch];
            float b = srcInterleavedStereo[static_cast<size_t>(nextIndex) * 2 + ch];
            out[static_cast<size_t>(i) * 2 + ch] = static_cast<float>(a + (b - a) * frac);
        }
    }

    *outFrameCount = dstFrameCount;
    return out;
}

} // namespace almus
