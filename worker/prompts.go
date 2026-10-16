package main

import (
	"embed"
	"fmt"
)

//go:embed schemas/*.json
var schemaFS embed.FS

// systemPrompt 见 docs/ai-contract.md「提示词骨架」。
const systemPrompt = `你是作业信息整理助手。只输出符合给定 JSON Schema 的单个 JSON 对象，不输出任何解释。
不确定的字段填 null 并写入 uncertain。不要猜测作业属于哪门课。`

// taskPrompts 每种任务的说明，对应 ai-contract.md 各小节。改契约时同步改这里。
var taskPrompts = map[string]string{
	"parse_assignment": `任务：从同学发的文字和/或截图里解析一条作业。
- title：提炼作业标题，不超过 30 个字，不照抄整段。
- requirements：作业要求（格式、范围、注意事项），没有就 null。
- ddl：截止时间，ISO 8601 且带 +08:00，例如 2026-10-23T13:55:00+08:00。
  相对日期按 context.today（星期 context.weekday，1=周一）和 context.class.slots 换算：
  「下节课前」= 下一个上课时段的开始时间（节次开始时间见 context.periods）；「下周五」= 下一周的周五；
  只说日期不说时间时默认 23:59。换算有歧义时 ddl 仍给最可能的值，同时把 "ddl" 放进 uncertain。
  完全没提截止时间就给 null 并把 "ddl" 放进 uncertain。
- ddl_source：原文里描述截止时间的那几个字，如「下周五上课前」，没有就 null。
- submit_method：提交方式，如「学习通作业」「纸质交课代表」，没有就 null。
- uncertain：拿不准的字段名数组，可为空数组。
不要判断这是哪门课的作业。`,

	"parse_timetable": `任务：识别易校园课表截图，输出每个课块。
- 每个课块一条：name 课名、room 教室、weekday 星期（1=周一 … 5=周五）、start/end 起止节（1–12）。
- 节次按左侧时间轴和课块高度对照 context.periods 的 12 节作息推算。
- 课块从截图上方被裁掉时 start_visible=false，start 给能看到的最早节；否则 start_visible=true。
- 课名显示不全（有省略号或被截断）时 truncated=true，name 填看得到的部分；否则 truncated=false。
- 有多张截图（上下两截）时按节次拼接，同一课块只输出一次。
- 教室规范化为「楼栋+房间号」，去掉 @ 和空格，如「经世楼G101」；看不到教室给 null。
- 灰色「非本周」的课也要输出。只输出周一到周五。
不要合并不同星期的课块，不要判断班级归属。`,

	"dedupe_review": `任务：判断草稿 draft 与每条候选作业 candidates 是否是同一份作业。
- 对每条候选输出一项：id（候选的 id）、same（是否同一份作业）、confidence（0–1）、reason（不超过 30 字的理由）。
- 同一章节/同一批题目且 DDL 相同或相近，多半是同一份；题目范围或要求明显不同则不是。
- 只能输出 candidates 里出现过的 id。`,

	"ddl_conflict": `任务：判断同一作业的各条来源 sources 对截止时间的说法是否矛盾。
- 综合每条来源的 stated_ddl 和正文 body 里提到的截止时间（相对日期按 context.today 换算）。
- 有两个及以上不同的截止时间时 conflict=true，values 列出这些时间（ISO 8601 带 +08:00，去重）；否则 conflict=false，values 为空数组。
- note：一句话说明依据，不超过 30 字；没有冲突时给空字符串。`,

	"spam_check": `任务：判断这条准备发布的内容是否像垃圾内容。
- suspicious：是否可疑。category 取 ad（广告）、unrelated（与作业无关）、abusive（辱骂）、gibberish（乱码/无意义）之一，不可疑时为 null。
- reason：一句话理由，不超过 30 字；不可疑时给空字符串。
- 正常的作业通知、课程信息、同学提问都不算可疑。拿不准时 suspicious=false。`,

	"reminder_text": `任务：为作业提醒生成通知文案。
- slot 表示提醒时点：2d=截止前 2 天、1d=截止前 1 天、12h=截止前 12 小时。
- title 和 body 各不超过 40 个字，不要表情，不要催促语气。
- title 点明课程和截止，如「概率论作业后天截止」；body 说清作业和截止时间，如「第三章习题，周五 13:55 前交到学习通」。`,
}

func loadSchemaText(kind string) (string, error) {
	b, err := schemaFS.ReadFile("schemas/" + kind + ".json")
	if err != nil {
		return "", fmt.Errorf("未知任务种类 %q", kind)
	}
	return string(b), nil
}
