package cordis.agent;

/**
 * 会话摘要：会话列表用，不含消息正文。
 */
public final class SessionInfo {

    public final String id;

    /** 创建时间（epoch millis） */
    public final long createdAt;

    /** 最后一条消息落库的时间（epoch millis） */
    public final long updatedAt;

    public final int messageCount;

    public SessionInfo(String id, long createdAt, long updatedAt, int messageCount) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messageCount = messageCount;
    }

    @Override
    public String toString() {
        return "Session(" + id + ", " + messageCount + " 条, updated=" + updatedAt + ")";
    }

}
