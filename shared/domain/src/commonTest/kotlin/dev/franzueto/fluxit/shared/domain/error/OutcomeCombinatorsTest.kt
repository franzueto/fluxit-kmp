package dev.franzueto.fluxit.shared.domain.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OutcomeCombinatorsTest {
    @Test
    fun map_error_on_ok_is_a_noop() {
        val ok: Outcome<Int, String> = Outcome.Ok(42)
        val mapped = ok.mapError { it.length }
        assertEquals(Outcome.Ok(42), mapped)
    }

    @Test
    fun map_error_on_err_transforms_the_error() {
        val err: Outcome<Int, String> = Outcome.Err("boom")
        val mapped = err.mapError { it.length }
        assertEquals(Outcome.Err(4), mapped)
    }

    @Test
    fun map_error_lifts_data_error_to_domain_error_via_to_domain() {
        // The headline call-site pattern Slice 6 enables.
        val repoResult: Outcome<String, DataError> = Outcome.Err(DataError.NotFound(id = "x"))
        val useCaseResult: Outcome<String, DomainError> =
            repoResult.mapError { it.toDomain(entity = "List") }
        val err = assertIs<Outcome.Err<DomainError>>(useCaseResult)
        assertEquals(DomainError.NotFound(entity = "List", id = "x"), err.error)
    }

    @Test
    fun fold_on_ok_invokes_ok_branch() {
        val ok: Outcome<Int, String> = Outcome.Ok(7)
        val folded = ok.fold(onOk = { "ok:$it" }, onErr = { "err:$it" })
        assertEquals("ok:7", folded)
    }

    @Test
    fun fold_on_err_invokes_err_branch() {
        val err: Outcome<Int, String> = Outcome.Err("nope")
        val folded = err.fold(onOk = { "ok:$it" }, onErr = { "err:$it" })
        assertEquals("err:nope", folded)
    }
}
