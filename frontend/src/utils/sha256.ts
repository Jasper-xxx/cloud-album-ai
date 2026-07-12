const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
  0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
  0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
  0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
  0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
  0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
  0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
  0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
  0xc67178f2,
])

const HEX = Array.from({ length: 256 }, (_, index) => index.toString(16).padStart(2, '0'))

export class Sha256 {
  private readonly state = new Uint32Array(8)
  private readonly buffer = new Uint8Array(64)
  private readonly words = new Uint32Array(64)
  private bufferLength = 0
  private bytesHashed = 0
  private finished = false

  constructor() {
    this.reset()
  }

  reset() {
    this.state[0] = 0x6a09e667
    this.state[1] = 0xbb67ae85
    this.state[2] = 0x3c6ef372
    this.state[3] = 0xa54ff53a
    this.state[4] = 0x510e527f
    this.state[5] = 0x9b05688c
    this.state[6] = 0x1f83d9ab
    this.state[7] = 0x5be0cd19
    this.bufferLength = 0
    this.bytesHashed = 0
    this.finished = false
    return this
  }

  update(data: ArrayBuffer | Uint8Array) {
    if (this.finished) {
      throw new Error('SHA-256 digest has already been finalized')
    }
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data)
    let position = 0
    this.bytesHashed += bytes.length

    if (this.bufferLength > 0) {
      while (this.bufferLength < 64 && position < bytes.length) {
        this.buffer[this.bufferLength++] = bytes[position++]
      }
      if (this.bufferLength === 64) {
        this.transform(this.buffer, 0)
        this.bufferLength = 0
      }
    }

    while (position + 64 <= bytes.length) {
      this.transform(bytes, position)
      position += 64
    }

    while (position < bytes.length) {
      this.buffer[this.bufferLength++] = bytes[position++]
    }
    return this
  }

  digest() {
    if (!this.finished) {
      const bytesHashed = this.bytesHashed
      const left = this.bufferLength
      this.buffer[left] = 0x80
      for (let i = left + 1; i < 64; i++) {
        this.buffer[i] = 0
      }
      if (left >= 56) {
        this.transform(this.buffer, 0)
        this.buffer.fill(0)
      }

      const bitHi = Math.floor(bytesHashed / 0x20000000)
      const bitLo = (bytesHashed << 3) >>> 0
      this.buffer[56] = bitHi >>> 24
      this.buffer[57] = bitHi >>> 16
      this.buffer[58] = bitHi >>> 8
      this.buffer[59] = bitHi
      this.buffer[60] = bitLo >>> 24
      this.buffer[61] = bitLo >>> 16
      this.buffer[62] = bitLo >>> 8
      this.buffer[63] = bitLo
      this.transform(this.buffer, 0)
      this.finished = true
    }

    let hex = ''
    for (let i = 0; i < this.state.length; i++) {
      const value = this.state[i]
      hex += HEX[(value >>> 24) & 0xff]
      hex += HEX[(value >>> 16) & 0xff]
      hex += HEX[(value >>> 8) & 0xff]
      hex += HEX[value & 0xff]
    }
    return hex
  }

  private transform(chunk: Uint8Array, offset: number) {
    const words = this.words
    for (let i = 0; i < 16; i++) {
      const j = offset + i * 4
      words[i] =
        ((chunk[j] << 24) | (chunk[j + 1] << 16) | (chunk[j + 2] << 8) | chunk[j + 3]) >>> 0
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(words[i - 15], 7) ^ rotr(words[i - 15], 18) ^ (words[i - 15] >>> 3)
      const s1 = rotr(words[i - 2], 17) ^ rotr(words[i - 2], 19) ^ (words[i - 2] >>> 10)
      words[i] = (words[i - 16] + s0 + words[i - 7] + s1) >>> 0
    }

    let a = this.state[0]
    let b = this.state[1]
    let c = this.state[2]
    let d = this.state[3]
    let e = this.state[4]
    let f = this.state[5]
    let g = this.state[6]
    let h = this.state[7]

    for (let i = 0; i < 64; i++) {
      const s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (h + s1 + ch + K[i] + words[i]) >>> 0
      const s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (s0 + maj) >>> 0
      h = g
      g = f
      f = e
      e = (d + temp1) >>> 0
      d = c
      c = b
      b = a
      a = (temp1 + temp2) >>> 0
    }

    this.state[0] = (this.state[0] + a) >>> 0
    this.state[1] = (this.state[1] + b) >>> 0
    this.state[2] = (this.state[2] + c) >>> 0
    this.state[3] = (this.state[3] + d) >>> 0
    this.state[4] = (this.state[4] + e) >>> 0
    this.state[5] = (this.state[5] + f) >>> 0
    this.state[6] = (this.state[6] + g) >>> 0
    this.state[7] = (this.state[7] + h) >>> 0
  }
}

export async function sha256HexFromFile(
  file: File,
  chunkSize: number,
  onProgress?: (processed: number) => void,
) {
  const sha256 = new Sha256()
  let processed = 0
  for (let offset = 0; offset < file.size; offset += chunkSize) {
    const buffer = await file.slice(offset, offset + chunkSize).arrayBuffer()
    sha256.update(buffer)
    processed += buffer.byteLength
    onProgress?.(processed)
  }
  return sha256.digest()
}

function rotr(value: number, shift: number) {
  return (value >>> shift) | (value << (32 - shift))
}
