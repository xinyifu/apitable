SET NAMES utf8mb4;

-- Self-host-owned automation catalog entries.
-- This file runs after upstream init-appdata, so it is the final state owner for
-- fork-specific automation services and action types.

UPDATE `__TABLE_PREFIX__automation_service`
SET
  `service_id` = 'asvWecom',
  `slug` = 'wecom',
  `name` = 'wecom',
  `description` = 'wecom',
  `logo` = '/static/icon/signin/signin_img_wecom.png',
  `base_url` = 'automation://wecom',
  `i18n` = JSON_OBJECT(
    'zh', JSON_OBJECT('wecom', '企业微信'),
    'en', JSON_OBJECT('wecom', 'WeCom')
  ),
  `is_deleted` = 0,
  `updated_by` = 0
WHERE `service_id` = 'asvWecom' OR `slug` = 'wecom';

INSERT INTO `__TABLE_PREFIX__automation_service`
  (`id`, `service_id`, `slug`, `name`, `description`, `logo`, `base_url`, `i18n`, `is_deleted`, `created_by`, `updated_by`)
SELECT
    202606220001,
    'asvWecom',
    'wecom',
    'wecom',
    'wecom',
    '/static/icon/signin/signin_img_wecom.png',
    'automation://wecom',
    JSON_OBJECT(
      'zh', JSON_OBJECT('wecom', '企业微信'),
      'en', JSON_OBJECT('wecom', 'WeCom')
    ),
    0,
    0,
    0
WHERE NOT EXISTS (
  SELECT 1 FROM `__TABLE_PREFIX__automation_service`
  WHERE `service_id` = 'asvWecom' OR `slug` = 'wecom'
);

UPDATE `__TABLE_PREFIX__automation_action_type`
SET
  `service_id` = 'asvWecom',
  `action_type_id` = 'aatSendWecomMsg',
  `name` = 'robot_action_send_wework_title',
  `description` = 'robot_action_send_wework_desc',
  `input_json_schema` = JSON_OBJECT(
    'schema', JSON_OBJECT(
      'type', 'object',
      'required', JSON_ARRAY('webhookUrl', 'type', 'content'),
      'properties', JSON_OBJECT(
        'webhookUrl', JSON_OBJECT(
          'type', 'string',
          'title', 'robot_action_send_wework_config_1',
          'description', 'robot_action_send_wework_config_1_desc'
        ),
        'type', JSON_OBJECT(
          'type', 'string',
          'title', 'robot_action_send_wework_config_2',
          'description', 'robot_action_send_wework_config_2_desc',
          'enum', JSON_ARRAY('text', 'markdown'),
          'enumNames', JSON_ARRAY('robot_action_send_wework_message_type_1', 'robot_action_send_wework_message_type_2'),
          'default', 'text'
        ),
        'content', JSON_OBJECT(
          'type', 'string',
          'title', 'robot_action_send_wework_config_3',
          'description', 'robot_action_send_wework_config_3_desc'
        )
      )
    ),
    'uiSchema', JSON_OBJECT(
      'ui:order', JSON_ARRAY('webhookUrl', 'type', 'content'),
      'webhookUrl', JSON_OBJECT('ui:widget', 'password'),
      'content', JSON_OBJECT('ui:widget', 'TextWidget')
    )
  ),
  `output_json_schema` = JSON_OBJECT(
    'schema', JSON_OBJECT(
      'type', 'object',
      'properties', JSON_OBJECT()
    ),
    'uiSchema', JSON_OBJECT()
  ),
  `endpoint` = 'sendWecomMsg',
  `i18n` = JSON_OBJECT(
    'zh', JSON_OBJECT(
      'robot_action_send_wework_title', '发送消息到企业微信群',
      'robot_action_send_wework_desc', '自动化开始运行后，会自动向指定企业微信群聊发送消息',
      'robot_action_send_wework_config_1', '企业微信机器人 webhook 地址',
      'robot_action_send_wework_config_1_desc', '指定一个企业微信机器人，向它所在的群聊发送消息',
      'robot_action_send_wework_config_2', '消息类型',
      'robot_action_send_wework_config_2_desc', '目前支持发送 text 和 markdown 类型的消息',
      'robot_action_send_wework_config_3', '消息内容',
      'robot_action_send_wework_config_3_desc', '输入要发送到企业微信群聊的消息内容（输入英文斜杠「/」可插入变量）',
      'robot_action_send_wework_message_type_1', '文本消息',
      'robot_action_send_wework_message_type_2', 'Markdown'
    ),
    'en', JSON_OBJECT(
      'robot_action_send_wework_title', 'Send a message to WeCom',
      'robot_action_send_wework_desc', 'When the automation starts working, it will automatically send a message to your WeCom chat group',
      'robot_action_send_wework_config_1', 'WeCom group robot webhook URL',
      'robot_action_send_wework_config_1_desc', 'Specify a WeCom group robot webhook to send messages to its chat group',
      'robot_action_send_wework_config_2', 'Message type',
      'robot_action_send_wework_config_2_desc', 'Currently only text and markdown messages are supported',
      'robot_action_send_wework_config_3', 'Message content',
      'robot_action_send_wework_config_3_desc', 'Enter the message to send to the WeCom chat group',
      'robot_action_send_wework_message_type_1', 'Text',
      'robot_action_send_wework_message_type_2', 'Markdown'
    )
  ),
  `is_deleted` = 0,
  `updated_by` = 0
WHERE `action_type_id` = 'aatSendWecomMsg' OR `endpoint` = 'sendWecomMsg';

INSERT INTO `__TABLE_PREFIX__automation_action_type`
  (`id`, `service_id`, `action_type_id`, `name`, `description`, `input_json_schema`, `output_json_schema`, `endpoint`, `i18n`, `is_deleted`, `created_by`, `updated_by`)
SELECT
    202606220002,
    'asvWecom',
    'aatSendWecomMsg',
    'robot_action_send_wework_title',
    'robot_action_send_wework_desc',
    JSON_OBJECT(
      'schema', JSON_OBJECT(
        'type', 'object',
        'required', JSON_ARRAY('webhookUrl', 'type', 'content'),
        'properties', JSON_OBJECT(
          'webhookUrl', JSON_OBJECT(
            'type', 'string',
            'title', 'robot_action_send_wework_config_1',
            'description', 'robot_action_send_wework_config_1_desc'
          ),
          'type', JSON_OBJECT(
            'type', 'string',
            'title', 'robot_action_send_wework_config_2',
            'description', 'robot_action_send_wework_config_2_desc',
            'enum', JSON_ARRAY('text', 'markdown'),
            'enumNames', JSON_ARRAY('robot_action_send_wework_message_type_1', 'robot_action_send_wework_message_type_2'),
            'default', 'text'
          ),
          'content', JSON_OBJECT(
            'type', 'string',
            'title', 'robot_action_send_wework_config_3',
            'description', 'robot_action_send_wework_config_3_desc'
          )
        )
      ),
      'uiSchema', JSON_OBJECT(
        'ui:order', JSON_ARRAY('webhookUrl', 'type', 'content'),
        'webhookUrl', JSON_OBJECT('ui:widget', 'password'),
        'content', JSON_OBJECT('ui:widget', 'TextWidget')
      )
    ),
    JSON_OBJECT(
      'schema', JSON_OBJECT(
        'type', 'object',
        'properties', JSON_OBJECT()
      ),
      'uiSchema', JSON_OBJECT()
    ),
    'sendWecomMsg',
    JSON_OBJECT(
      'zh', JSON_OBJECT(
        'robot_action_send_wework_title', '发送消息到企业微信群',
        'robot_action_send_wework_desc', '自动化开始运行后，会自动向指定企业微信群聊发送消息',
        'robot_action_send_wework_config_1', '企业微信机器人 webhook 地址',
        'robot_action_send_wework_config_1_desc', '指定一个企业微信机器人，向它所在的群聊发送消息',
        'robot_action_send_wework_config_2', '消息类型',
        'robot_action_send_wework_config_2_desc', '目前支持发送 text 和 markdown 类型的消息',
        'robot_action_send_wework_config_3', '消息内容',
        'robot_action_send_wework_config_3_desc', '输入要发送到企业微信群聊的消息内容（输入英文斜杠「/」可插入变量）',
        'robot_action_send_wework_message_type_1', '文本消息',
        'robot_action_send_wework_message_type_2', 'Markdown'
      ),
      'en', JSON_OBJECT(
        'robot_action_send_wework_title', 'Send a message to WeCom',
        'robot_action_send_wework_desc', 'When the automation starts working, it will automatically send a message to your WeCom chat group',
        'robot_action_send_wework_config_1', 'WeCom group robot webhook URL',
        'robot_action_send_wework_config_1_desc', 'Specify a WeCom group robot webhook to send messages to its chat group',
        'robot_action_send_wework_config_2', 'Message type',
        'robot_action_send_wework_config_2_desc', 'Currently only text and markdown messages are supported',
        'robot_action_send_wework_config_3', 'Message content',
        'robot_action_send_wework_config_3_desc', 'Enter the message to send to the WeCom chat group',
        'robot_action_send_wework_message_type_1', 'Text',
        'robot_action_send_wework_message_type_2', 'Markdown'
      )
    ),
    0,
    0,
    0
WHERE NOT EXISTS (
  SELECT 1 FROM `__TABLE_PREFIX__automation_action_type`
  WHERE `action_type_id` = 'aatSendWecomMsg' OR `endpoint` = 'sendWecomMsg'
);
