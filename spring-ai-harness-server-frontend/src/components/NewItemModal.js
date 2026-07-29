import React, { useState } from 'react';
import { Modal, Form, Input, Radio, message } from 'antd';
import { FileTextOutlined, FolderAddOutlined } from '@ant-design/icons';

export const NewItemModal = ({ visible, onCancel, onCreate, currentPath }) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      setLoading(true);

      let targetPath = currentPath
        ? `${currentPath.replace(/\/$/, '')}/${values.name.trim()}`
        : values.name.trim();

      if (values.type === 'folder' && !targetPath.endsWith('/')) {
        targetPath += '/.gitkeep'; // Create dummy marker file for OSS folder
      }

      await onCreate(targetPath, values.type === 'folder' ? '' : (values.content || ''));
      message.success(`Successfully created ${values.type === 'folder' ? 'folder' : 'file'}: ${values.name}`);
      form.resetFields();
      onCancel();
    } catch (err) {
      if (err.message) {
        message.error(err.message);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title="Create New Item"
      visible={visible}
      onOk={handleOk}
      onCancel={onCancel}
      confirmLoading={loading}
      okText="Create"
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ type: 'file' }}
      >
        <Form.Item name="type" label="Item Type">
          <Radio.Group buttonStyle="solid">
            <Radio.Button value="file">
              <FileTextOutlined style={{ marginRight: 6 }} /> Plain Text File
            </Radio.Button>
            <Radio.Button value="folder">
              <FolderAddOutlined style={{ marginRight: 6 }} /> Folder
            </Radio.Button>
          </Radio.Group>
        </Form.Item>

        <Form.Item
          name="name"
          label="Item Name"
          rules={[{ required: true, message: 'Please input item name' }]}
        >
          <Input placeholder="e.g. README.md or docs" />
        </Form.Item>

        <Form.Item
          noStyle
          shouldUpdate={(prevValues, currentValues) => prevValues.type !== currentValues.type}
        >
          {({ getFieldValue }) =>
            getFieldValue('type') === 'file' ? (
              <Form.Item name="content" label="Initial Content (Optional)">
                <Input.TextArea
                  rows={4}
                  placeholder="Enter initial file content..."
                  className="code-editor-textarea"
                />
              </Form.Item>
            ) : null
          }
        </Form.Item>
      </Form>
    </Modal>
  );
};
