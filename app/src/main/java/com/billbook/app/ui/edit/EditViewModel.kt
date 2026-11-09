package com.billbook.app.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.billbook.app.BillBookApp
import com.billbook.app.data.Profile
import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import com.billbook.app.data.TypeCategory
import com.billbook.app.data.date
import com.billbook.app.ocr.KnownNames
import com.billbook.app.ocr.ReceiptParser
import com.billbook.app.ocr.ReceiptScanner
import com.billbook.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class FormState(
    val profileId: Long = Profile.DEFAULT_ID,
    val type: TxType = TxType.EXPENSE,
    val amount: String = "",
    val currency: String = "",
    val tax: String = "",
    val date: LocalDate = LocalDate.now(),
    val party: String = "",
    val billNumber: String = "",
    val category: String = "",
    val paymentMethod: String = "",
    val notes: String = "",
    val imagePath: String? = null,
    val ocrText: String? = null,
) {
    companion object {
        fun from(t: Transaction) = FormState(
            profileId = t.profileId,
            type = t.type,
            amount = Money.toInput(t.amountCents),
            currency = t.currency,
            tax = if (t.taxCents == 0L) "" else Money.toInput(t.taxCents),
            date = t.date,
            party = t.party,
            billNumber = t.billNumber,
            category = t.category,
            paymentMethod = t.paymentMethod,
            notes = t.notes,
            imagePath = t.imagePath,
            ocrText = t.ocrText,
        )
    }
}

/** Other values the scan found for a field, offered as one-tap alternatives (best first). */
data class ScanSuggestions(
    val amounts: List<String> = emptyList(),
    val dates: List<LocalDate> = emptyList(),
    val parties: List<String> = emptyList(),
    val billNumbers: List<String> = emptyList(),
)

class EditViewModel(
    private val app: BillBookApp,
    private val id: Long,
    type: TxType,
    imagePath: String?,
    /** When > 0, the form is pre-filled from a bank notification detection. */
    private val detectedId: Long = 0,
) : ViewModel() {

    private val dao = app.database.transactions()
    private val scanner = ReceiptScanner(app)
    private var existing: Transaction? = null
    private var saved = false
    private var dateTouched = false
    private var currencyTouched = false

    var form by mutableStateOf(
        FormState(
            profileId = app.settings.activeProfileId.value,
            type = type,
            imagePath = imagePath,
            currency = app.settings.currency,
        )
    )
        private set
    var scanning by mutableStateOf(false)
        private set
    var scanMessage by mutableStateOf<String?>(null)
        private set
    var suggestions by mutableStateOf(ScanSuggestions())
        private set
    var amountError by mutableStateOf<String?>(null)
        private set
    var taxError by mutableStateOf<String?>(null)
        private set

    val isExisting: Boolean get() = id > 0

    val profiles: StateFlow<List<Profile>> = app.database.profiles().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val usedCategories: StateFlow<List<TypeCategory>> = dao.observeUsedCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (id > 0) {
            viewModelScope.launch {
                dao.get(id)?.let { t ->
                    existing = t
                    form = FormState.from(t).let { f ->
                        if (f.currency.isBlank()) f.copy(currency = app.settings.currency) else f
                    }
                    dateTouched = true
                    currencyTouched = true
                }
            }
        } else if (detectedId > 0) {
            viewModelScope.launch {
                app.bankAlerts.get(detectedId)?.let { d ->
                    form = FormState.from(app.bankAlerts.toTransaction(d, app.settings.activeProfileId.value))
                    dateTouched = true
                    currencyTouched = true
                }
            }
        } else if (imagePath != null) {
            scan(overwrite = false)
        }
    }

    fun update(transform: (FormState) -> FormState) {
        form = transform(form)
        amountError = null
        taxError = null
    }

    /** Adds a piece of a name to the business field, dropping words the two share. */
    fun appendParty(piece: String) = update { it.copy(party = ReceiptParser.joinPieces(it.party, piece)) }

    fun setDate(date: LocalDate) {
        dateTouched = true
        update { it.copy(date = date) }
    }

    fun setCurrency(value: String) {
        currencyTouched = true
        update { it.copy(currency = value.filter { c -> c.isLetter() }.uppercase().take(3)) }
    }

    /** Reads the photo again and replaces fields with whatever is detected. */
    fun rescan() = scan(overwrite = true)

    private fun scan(overwrite: Boolean) {
        val path = form.imagePath ?: return
        if (scanning) return
        scanning = true
        scanMessage = "Reading bill…"
        viewModelScope.launch {
            try {
                val result = scanner.scan(path, app.settings.currency)
                applyScan(result, overwrite, known = dao.knownParties())
            } catch (e: Exception) {
                scanMessage = "Couldn't read the photo (${e.message ?: "unknown error"}). Please enter details manually."
            } finally {
                scanning = false
            }
        }
    }

    private fun applyScan(result: ReceiptScanner.Result, overwrite: Boolean, known: List<String>) {
        val p = result.parsed
        // A business saved before wins when the scan resembles it: the user's own spelling is
        // right even where OCR misread a word or split the name.
        val knownMatch = KnownNames.bestMatch(p.merchantOptions, known)
        val merchant = knownMatch ?: p.merchant
        fun pick(current: String, found: String?) =
            if (found != null && (overwrite || current.isBlank())) found else current

        val f = form
        form = f.copy(
            amount = pick(f.amount, p.totalCents?.let(Money::toInput)),
            tax = pick(f.tax, p.taxCents?.let(Money::toInput)),
            party = pick(f.party, merchant),
            billNumber = pick(f.billNumber, p.billNumber),
            category = pick(f.category, p.category),
            paymentMethod = pick(f.paymentMethod, p.paymentMethod),
            date = if (p.date != null && (overwrite || !dateTouched)) p.date else f.date,
            currency = if (p.currency != null && (overwrite || !currencyTouched)) p.currency else f.currency,
            ocrText = result.text,
        )
        suggestions = ScanSuggestions(
            amounts = p.totalOptions.map(Money::toInput),
            dates = p.dateOptions,
            parties = (listOfNotNull(knownMatch) + p.merchantOptions).distinctBy(KnownNames::normalize),
            billNumbers = p.billNumberOptions,
        )

        val found = listOfNotNull(
            p.totalCents?.let { "amount" },
            p.currency?.let { "currency ($it)" },
            p.date?.let { "date" },
            merchant?.let { "business" },
            p.billNumber?.let { "bill no." },
            p.taxCents?.let { "tax" },
            p.paymentMethod?.let { "payment method" },
        )
        scanMessage = when {
            result.text.isBlank() -> "No text found. Try a clearer, well-lit photo, or enter details manually."
            found.isEmpty() -> "Text was read but no details were recognised. Please fill in the fields."
            else -> "Detected ${found.joinToString(", ")}. Please check before saving."
        }
    }

    fun save(onSaved: () -> Unit) {
        if (saved) return
        val f = form
        val amount = Money.parseToCents(f.amount)
        if (amount == null || amount <= 0) {
            amountError = "Enter an amount greater than 0"
            return
        }
        val tax = if (f.tax.isBlank()) 0L else Money.parseToCents(f.tax)
        if (tax == null || tax < 0) {
            taxError = "Enter a valid tax amount"
            return
        }
        val transaction = Transaction(
            id = existing?.id ?: 0,
            profileId = f.profileId,
            type = f.type,
            amountCents = amount,
            currency = f.currency.trim().uppercase().ifBlank { app.settings.currency },
            taxCents = tax,
            dateEpochDay = f.date.toEpochDay(),
            party = f.party.trim(),
            billNumber = f.billNumber.trim(),
            category = f.category.trim(),
            paymentMethod = f.paymentMethod.trim(),
            notes = f.notes.trim(),
            imagePath = f.imagePath,
            ocrText = f.ocrText,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        saved = true
        viewModelScope.launch {
            val newId = dao.upsert(transaction)
            if (detectedId > 0 && newId > 0) app.bankAlerts.markAdded(detectedId, newId)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val t = existing ?: return
        saved = true
        viewModelScope.launch {
            dao.delete(t)
            t.imagePath?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
            onDeleted()
        }
    }

    override fun onCleared() {
        scanner.close()
        // A new bill photo that was never saved should not stay on the device.
        if (!saved && existing == null) form.imagePath?.let { File(it).delete() }
    }

    companion object {
        fun factory(id: Long, type: TxType, imagePath: String?, detectedId: Long = 0) = viewModelFactory {
            initializer { EditViewModel(this[APPLICATION_KEY] as BillBookApp, id, type, imagePath, detectedId) }
        }
    }
}
