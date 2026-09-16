package cl.iac33.app.update

import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult
import cl.iac33.app.core.UpdateInfo

object UpdateValidator {
    fun validate(info: UpdateInfo): OperationResult<UpdateInfo> {
        if (info.releaseId.isBlank() || info.version.isBlank()) return OperationResult.Failure(OperationError.VALIDATION, "Invalid release identity")
        if (info.sizeBytes <= 0L) return OperationResult.Failure(OperationError.VALIDATION, "Invalid artifact size")
        if (!info.sha256.matches(Regex("[0-9a-fA-F]{64}"))) return OperationResult.Failure(OperationError.INTEGRITY, "Invalid SHA-256")
        return OperationResult.Success(info)
    }
}
