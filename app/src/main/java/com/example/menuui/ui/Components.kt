package com.example.menuui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 共享表单组件。数字输入框约定：本地文本态与外部数值解耦——输入过程中的
 * 非法中间态（如 "39."、"-"）留在本地不提交，解析合法且通过 valid 校验才提交；
 * 不合法时输入框标红并给出原因（不再静默丢弃）。外部值变化（预设载入）时重置文本。
 * 配置里永远只有合法数值。
 */
object UiComponents

/** 页面统一外边距与卡片间距 */
val PagePadding = 12.dp
val SectionSpacing = 12.dp

/**
 * 分区卡片。collapsible=true 时标题行可点击折叠，折叠态只显示 subtitle（适合放
 * 摘要，如"3 个基站"）；展开状态按 title 记忆（切换页签后保持）。
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val open = !collapsible || expanded
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (collapsible) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                    )
                }
            }
            if (open) {
                Column(
                    Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { content() }
            }
        }
    }
}

/** 小节标题（卡片内分组） */
@Composable
fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** 次要说明文字 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * 数值输入框通用实现：parse 失败 → "请输入数字"；validate 返回非 null → 该原因。
 * 两种情况都标红且不提交。
 */
@Composable
private fun <T> NumberField(
    label: String,
    value: T,
    format: (T) -> String,
    parse: (String) -> T?,
    onCommit: (T) -> Unit,
    validate: (T) -> String?,
    keyboardType: KeyboardType,
    modifier: Modifier,
    suffix: String,
) {
    var text by remember(value) { mutableStateOf(format(value)) }
    val parsed = parse(text.trim())
    val error = when {
        parsed == null -> "请输入数字"
        else -> validate(parsed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            val v = parse(t.trim()) ?: return@OutlinedTextField
            if (validate(v) == null) onCommit(v)
        },
        label = { Text(label) },
        suffix = textSlot(suffix.ifEmpty { null }),
        isError = error != null,
        supportingText = textSlot(error),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun DoubleField(
    label: String,
    value: Double,
    onCommit: (Double) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = "",
    validate: (Double) -> String? = { null },
) = NumberField(
    label, value, ::formatDouble, { it.toDoubleOrNull()?.takeIf { d -> d.isFinite() } },
    onCommit, validate, KeyboardType.Decimal, modifier, suffix,
)

@Composable
fun IntField(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    validate: (Int) -> String? = { null },
) = NumberField(
    label, value, { it.toString() }, { it.toIntOrNull() },
    onCommit, validate, KeyboardType.Number, modifier, "",
)

@Composable
fun LongField(
    label: String,
    value: Long,
    onCommit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    validate: (Long) -> String? = { null },
) = NumberField(
    label, value, { it.toString() }, { it.toLongOrNull() },
    onCommit, validate, KeyboardType.Number, modifier, "",
)

/** 常用范围校验 */
fun rangeCheck(min: Double, max: Double): (Double) -> String? =
    { v -> if (v < min || v > max) "范围 ${formatDouble(min)} ~ ${formatDouble(max)}" else null }

@Composable
fun StrField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onCommit,
        label = { Text(label) },
        placeholder = textSlot(hint.ifEmpty { null }),
        isError = error != null,
        supportingText = textSlot(error),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 纯数字串（MCC/MNC 等保留前导零的编码）：只接受数字、超长截断，位数不在
 * [lengths] 内时标红提示（不在输入过程中补零——此前 MCC 每次按键都 padEnd，
 * 输入"4"立刻变"400"无法正常编辑）。
 */
@Composable
fun DigitsField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    lengths: IntRange,
    modifier: Modifier = Modifier,
) {
    val error = if (value.length !in lengths) {
        if (lengths.first == lengths.last) "需 ${lengths.first} 位数字" else "需 ${lengths.first}~${lengths.last} 位数字"
    } else null
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onCommit(t.filter { it.isDigit() }.take(lengths.last)) },
        label = { Text(label) },
        isError = error != null,
        supportingText = textSlot(error),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 单选 chips 行（速度档位 / 制式等）；放不下时换行 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/** 破坏性操作确认（删除 / 恢复默认），确认按钮用错误色 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 文本插槽（supportingText / suffix 等）：显式声明 @Composable 函数类型，null = 不显示 */
private fun textSlot(text: String?): (@Composable () -> Unit)? =
    if (text == null) null else ({ Text(text) })

fun formatDouble(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
