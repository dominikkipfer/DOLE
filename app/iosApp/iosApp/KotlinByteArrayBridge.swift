import Foundation
import shared

nonisolated func int64Value(_ value: Any?) -> Int64 {
    if let value = value as? Int64 {
        return value
    }
    if let value = value as? NSNumber {
        return value.int64Value
    }
    return 0
}

nonisolated func data(from bytes: KotlinByteArray) -> Data {
    let count = Int(bytes.size)
    var data = Data(capacity: count)
    for index in 0..<count {
        data.append(UInt8(bitPattern: bytes.get(index: Int32(index))))
    }
    return data
}

nonisolated func kotlinByteArray(from data: Data) -> KotlinByteArray {
    let result = KotlinByteArray(size: Int32(data.count))
    for (index, byte) in data.enumerated() {
        result.set(index: Int32(index), value: Int8(bitPattern: byte))
    }
    return result
}
