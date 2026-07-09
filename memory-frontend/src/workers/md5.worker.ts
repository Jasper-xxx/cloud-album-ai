import SparkMD5 from 'spark-md5'
import { Sha256 } from '../utils/sha256'

self.onmessage = async (event: MessageEvent<File>) => {
  const file = event.data
  const spark = new SparkMD5.ArrayBuffer()
  const sha256 = new Sha256()
  const chunkSize = 16 * 1024 * 1024
  let processed = 0

  try {
    for (let offset = 0; offset < file.size; offset += chunkSize) {
      const buffer = await file.slice(offset, offset + chunkSize).arrayBuffer()
      spark.append(buffer)
      sha256.update(buffer)
      processed += buffer.byteLength
      self.postMessage({
        progress: file.size ? Math.min(100, (processed / file.size) * 100) : 100,
      })
    }
    self.postMessage({ md5: spark.end(), sha256: sha256.digest() })
  } catch (error) {
    self.postMessage({
      error: error instanceof Error ? error.message : '文件校验失败',
    })
  }
}
