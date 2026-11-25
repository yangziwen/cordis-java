package cordis.agent.fs;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 适配层内部的 JSON 小件：共享一个 {@link ObjectMapper}（它是线程安全且
 * 构造有成本的），并提供给模型看的错误负载形状。
 *
 * <p>注意它与 {@code cordis.agent.ToolError}（包私有，纯文本拼接）不是一个
 * 东西：那边是 loop 层给工具异常兜底的回填；这边是工具<b>主动</b>返回的结构化
 * 结果 / 参数错误信息。本包与 {@code agent.llm} 一样，允许依赖 Jackson——
 * 协议塑形本来就是适配层的活。
 */
final class JsonCodec {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {
    }

}
