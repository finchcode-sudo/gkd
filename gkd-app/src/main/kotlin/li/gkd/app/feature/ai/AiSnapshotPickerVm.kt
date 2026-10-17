package li.gkd.app.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import li.gkd.app.appScope
import li.gkd.app.data.ComplexSnapshot
import li.gkd.app.data.NodeInfo
import li.gkd.app.data.snapshot.SnapshotRepository
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.util.LogUtils
import li.gkd.app.util.keepNullJson
import li.gkd.app.util.launchLogged

fun NodeInfo.toSelectedComponent(): AiSelectedComponent = AiSelectedComponent(
    text = attr.text,
    desc = attr.desc,
    vid = attr.vid ?: attr.id?.substringAfterLast('/', attr.id),
    name = attr.name,
    bounds = "${attr.left},${attr.top},${attr.right},${attr.bottom}",
    clickable = attr.clickable,
    longClickable = attr.longClickable,
)

class AiSnapshotPickerVm : BaseViewModel() {

    private val snapshotMutable = MutableStateFlow<ComplexSnapshot?>(null)
    val snapshot: StateFlow<ComplexSnapshot?> = snapshotMutable

    fun loadSnapshot(snapshotId: Long) {
        if (snapshotMutable.value?.id == snapshotId) return
        scope.launchUi {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    keepNullJson.decodeFromString<ComplexSnapshot>(
                        SnapshotRepository.snapshotFile(snapshotId).readText()
                    )
                }.onFailure { e ->
                    // 快照文件缺失/损坏时静默失败会让选择页变成空白，这里留一条日志
                    LogUtils.d("加载快照失败: $snapshotId", e)
                }.getOrNull()
            }
            snapshotMutable.value = loaded
        }
    }

    fun screenshotFile(snapshotId: Long) = SnapshotRepository.screenshotFile(snapshotId)

    /**
     * 对话生成：记录选中的组件列表到会话，跳转会话页输入要求
     */
    fun startConversation(
        snapshotId: Long,
        appId: String,
        nodes: List<NodeInfo>,
        point: Pair<Int, Int>? = null,
    ) {
        scope.launchUi {
            AiRuleLauncher.ensureConversation(snapshotId, appId)
            AiConversationStore.setSelectedComponents(
                snapshotId,
                nodes.map { it.toSelectedComponent() },
            )
            if (point != null) {
                AiConversationStore.appendMessage(
                    snapshotId = snapshotId,
                    message = AiMessage(
                        role = "user",
                        content = pointHint(point),
                        time = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** 用户在截图上标出的点击位置，写给 AI 的说明 */
    private fun pointHint(point: Pair<Int, Int>): String =
        "我要点击的位置是屏幕坐标 (${point.first}, ${point.second})。" +
            "那里是画出来的按钮，没有独立的无障碍节点，请用 action 为 clickCenter，" +
            "position 写 {\"x\": \"${point.first}\", \"y\": \"${point.second}\"}，" +
            "matches 只用来判断弹窗已出现。" +
            "注意：节点树里可能还保留着被弹窗盖住的底层页面（比如首页），" +
            "不要拿它们当 matches 或点击目标；desc 按我在截图上看到的按钮来写。"

    /**
     * 直接生成：记录选中组件列表并后台生成规则。
     * 会话创建、组件写入、消息追加、发起生成必须按序完成，否则生成时可能读不到选中组件。
     */
    fun generateDirect(
        snapshotId: Long,
        appId: String,
        nodes: List<NodeInfo>,
        point: Pair<Int, Int>? = null,
    ) {
        appScope.launchLogged {
            AiRuleLauncher.ensureConversation(snapshotId, appId)
            AiConversationStore.setSelectedComponents(
                snapshotId,
                nodes.map { it.toSelectedComponent() },
            )
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "user",
                    content = "请针对我选中的组件生成规则。" +
                        (point?.let { "\n" + pointHint(it) } ?: ""),
                    time = System.currentTimeMillis(),
                ),
            )
            AiRuleGenerator.startConversationRule(snapshotId, notifyUser = true)
        }
    }
}
