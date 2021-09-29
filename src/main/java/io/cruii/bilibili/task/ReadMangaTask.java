package io.cruii.bilibili.task;

import cn.hutool.json.JSONObject;
import io.cruii.bilibili.entity.TaskConfig;
import lombok.extern.log4j.Log4j2;

/**
 * @author cruii
 * Created on 2021/9/22
 */
@Log4j2
public class ReadMangaTask extends AbstractTask {
    public ReadMangaTask(TaskConfig config) {
        super(config);
    }

    @Override
    public void run() {
        JSONObject resp = delegate.readManga();
        if (resp.getInt(CODE) == 0) {
            log.info("完成漫画阅读 ✔️");
        } else {
            log.info("阅读失败：{} ❌", resp);
        }
    }

    @Override
    public String getName() {
        return "阅读漫画";
    }
}
