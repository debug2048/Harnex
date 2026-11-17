// Generates a message card (消息卡片 / 普通卡片), not a standard card.
// Envelope, Card and ButtonList shapes follow the official approval example:
// https://github.com/open-dingtalk/dingtalk-card-examples/tree/main/examples/审批模板
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const dynamic = name => ({ type: 'dynamicString', content: '${' + name + '}', i18n: false })
const isTrue = name => ({ op: 'and', conditions: [
  { type: 'variable', variable: name, variableType: 'global', op: 'isTrue', valueType: 'fixed', value: '', valueVariableType: 'global' },
] })
const visible = name => ({ type: 'dynamicVisible', value: true, valueType: 'condition', condition: isTrue(name) })
const node = (componentName, id, props) => ({ componentName, id, props, title: '', hidden: false, isLocked: false, condition: true, conditionGroup: '' })
const variables = [
  ['title', 'string', false], ['statusText', 'string', false], ['code', 'string', false],
  ['body', 'markdown', false], ['feedback', 'markdown', true], ['actionSucceeded', 'boolean', true],
  ...Array.from({ length: 6 }, (_, index) => [[`button${index}Text`, 'string', false], [`button${index}Action`, 'string', false], [`button${index}Visible`, 'boolean', false]]).flat(),
].map(([name, type, privateValue]) => ({ name, id: name, type, private: privateValue, description: name, editorVarType: 'variables' }))
const markdown = name => node('Markdown', `harnex_${name}`, { mdVer: 0, marginLeft: 12, marginRight: 12, marginTop: 12, marginBottom: 4,
  content: { type: 'dynamicMarkdown', valueType: 'variable', value: '', variable: name, variableType: 'global' } })
const button = index => ({ id: `harnex_button_${index}`, '$$key': String(index), text: dynamic(`button${index}Text`),
  status: 'normal', color: index === 0 ? 'blue' : 'gray', actionType: 'request',
  actionId: dynamic(`button${index}Action`), disabledWhileForward: true,
  params: [{ type: 'variable', variable: `button${index}Action`, name: 'action', variableType: 'global', id: `action_${index}`, value: '' }],
  successCondition: isTrue('actionSucceeded'),
  successToast: { type: 'dynamicString', content: '已处理，请查看卡片或会话中的结果', i18n: false },
  failureToast: { type: 'dynamicString', content: '未能处理，请查看卡片提示或发送 /状态', i18n: false },
  visible: visible(`button${index}Visible`),
})
const mock = { title: '批准修改文件', statusText: '等待你的处理', code: 'A1B2C3D4',
  body: '发起人：示例用户\n工作区：示例项目\n\n文件：src/example.ts\n将修改此文件。可点击查看详情。\n\n截止时间会随请求显示；仅批准这一次操作。', feedback: '', actionSucceeded: false }
for (let i = 0; i < 6; i++) {
  mock[`button${i}Text`] = ['仅批准本次', '拒绝', '查看详情', '取消任务', '', ''][i]
  mock[`button${i}Action`] = ['approve', 'deny', 'details', 'cancel', '', ''][i]
  mock[`button${i}Visible`] = i < 4
}
const card = node('Card', 'harnex_im_interaction', { showCloseButton: false, enableClickEvent: false,
  summaryContent: { type: 'variableValue', variable: 'title', variableType: 'global' } })
Object.assign(card, { fileName: 'Harnex IM interaction', dataSource: { list: [] }, state: {}, lifeCycles: {}, methods: {},
  children: [node('CardHeaderV2', 'harnex_title', { title: dynamic('title') }),
    node('BaseText', 'harnex_status', { text: dynamic('statusText'), marginLeft: 12, marginRight: 12,
      marginTop: 8, marginBottom: 0, fontColorType: 'Standard', color: 'gray', fontSizeType: 'Standard', size: 'middle' }),
    markdown('body'), ...Array.from({ length: 3 }, (_, row) => node('ButtonList', `harnex_buttons_${row}`, {
    direction: 'horizontal', widthMode: 'AutoWidth', marginLeft: 12, marginRight: 12, marginTop: 6, marginBottom: 6,
    visible: visible(`button${row * 2}Visible`),
    buttons: { type: 'button', list: [button(row * 2), button(row * 2 + 1)] },
  })), markdown('feedback')],
})
const editor = { schemaVersion: '2.0.0', schema: { version: '1.0.0', componentsMap: ['Card', 'CardHeaderV2', 'BaseText', 'Markdown', 'ButtonList'].map(name => ({
  package: '@ali/dxComponent', version: '1.0.0', exportName: name, main: './src/index.tsx', destructuring: false, subName: '', componentName: name,
})), componentsTree: [card] }, mockData: { cardData: mock, cardPrivateData: { feedback: '', actionSucceeded: false }, localData: {} },
  editVersion: 0, customWidgetInfo: '', useCustomWidgetInfo: false, variableList: variables,
  formList: [], expList: [], localList: [], hsfList: [], lwpList: [], extension: { extendType: 'NORMAL', fileTypeList: [] },
}
const target = resolve(root, 'backend/src/main/resources/im/dingtalk-interaction-card.json')
mkdirSync(dirname(target), { recursive: true })
writeFileSync(target, JSON.stringify({ editorData: JSON.stringify(editor), widgetInfo: '', type: 'im', mode: 'card' }, null, 2) + '\n')
console.log('Generated editable DingTalk interaction template.')
