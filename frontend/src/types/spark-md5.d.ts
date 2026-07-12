declare module 'spark-md5' {
  class SparkMD5ArrayBuffer {
    append(data: ArrayBuffer): this
    end(raw?: boolean): string
    reset(): this
  }

  const SparkMD5: {
    ArrayBuffer: typeof SparkMD5ArrayBuffer
  }

  export default SparkMD5
}
