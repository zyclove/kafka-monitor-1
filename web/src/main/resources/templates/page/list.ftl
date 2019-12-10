﻿<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <#include "../common/layui.ftl">
</head>
<body>
<div class="layui-fluid">
    <div class="layui-card">
        <div class="layui-form layui-card-header layuiadmin-card-header-auto">
            <div class="layui-form-item">
                <div class="layui-inline">页面名称</div>
                <div class="layui-inline" style="width:500px">
                    <input type="text" name="name" placeholder="请输入页面名称" autocomplete="off" class="layui-input">
                </div>
                <div class="layui-inline">
                    <button class="layui-btn layuiadmin-btn-admin" lay-submit lay-filter="search">
                        <i class="layui-icon layui-icon-search layuiadmin-button-btn"></i>
                    </button>
                </div>
            </div>
        </div>

        <div class="layui-card-body">
            <table class="layui-hide" id="grid" lay-filter="grid"></table>
            <script type="text/html" id="grid-toolbar">
                <div class="layui-btn-container">
                    <@insert>
                        <button class="layui-btn layui-btn-sm layuiadmin-btn-admin" lay-event="add">添加</button>
                    </@insert>
                    <@delete>
                        <button class="layui-btn layui-btn-sm" lay-event="batchDel">删除</button>
                    </@delete>
                </div>
            </script>

            <script type="text/html" id="grid-bar">
                <@update>
                    <a class="layui-btn layui-btn-normal layui-btn-xs" lay-event="edit"><i
                                class="layui-icon layui-icon-edit"></i>编辑</a>
                </@update>
                <@delete>
                    <a class="layui-btn layui-btn-danger layui-btn-xs" lay-event="del"><i
                                class="layui-icon layui-icon-delete"></i>删除</a>
                </@delete>
            </script>
        </div>

    </div>
</div>
<script type="text/javascript">
    layui.config({base: '../../..${ctx}/layuiadmin/'}).extend({index: 'lib/index'}).use(['index', 'table'], function () {
        const admin = layui.admin, form = layui.form, table = layui.table;
        form.on('submit(search)', function (data) {
            const field = data.field;
            table.reload('grid', {where: field});
        });
        table.render({
            elem: '#grid',
            url: 'list',
            toolbar: '#grid-toolbar',
            method: 'post',
            cellMinWidth: 80,
            page: true,
            limit: 15,
            even: true,
            cols: [[
                {type: 'checkbox'},
                {type: 'numbers', title: '序号'},
                {field: 'name', title: '页面名称', width: 200},
                {field: 'parentName', title: '父级菜单', width: 150},
                {field: 'url', title: 'URL地址', width: 200},
                {
                    title: '菜单图标', width: 100, templet: function (d) {
                        return '<i class="layui-icon ' + d.iconClass + '" style="color: #1E9FFF;"></i>';
                    }
                },
                {
                    title: '是否是菜单', width: 120, templet: function (d) {
                        return d.isMenu ? "是" : "否"
                    }
                },
                {
                    title: '是否首页', width: 120, templet: function (d) {
                        return d.isDefault ? "是" : "否"
                    }
                },
                {
                    title: '是否新窗口', width: 120, templet: function (d) {
                        return d.isBlank ? "是" : "否"
                    }
                },
                {field: 'description', title: '描述信息'}
                <@select>
                , {fixed: 'right', title: '操作', toolbar: '#grid-bar', width: 160}
                </@select>
            ]]
        });

        table.on('toolbar(grid)', function (obj) {
            const checkStatus = table.checkStatus(obj.config.id);
            switch (obj.event) {
                case 'batchDel':
                    const data = checkStatus.data;
                    if (data.length > 0) {
                        layer.confirm(admin.DEL_QUESTION, function (index) {
                            let keys = "";
                            for (let j = 0, len = data.length; j < len; j++) {
                                keys = keys + data[j].id + ","
                            }
                            admin.post("del", {ids: keys}, function () {
                                table.reload('grid');
                                layer.close(index);
                            });
                        });
                    } else {
                        admin.error(admin.SYSTEM_PROMPT, admin.DEL_ERROR);
                    }
                    break;
                case 'add':
                    layer.open({
                        type: 2,
                        title: admin.ADD,
                        content: 'toadd.html',
                        area: ['400px', '560px'],
                        btn: admin.BUTTONS,
                        resize: false,
                        yes: function (index, layero) {
                            const iframeWindow = window['layui-layer-iframe' + index], submitID = 'btn_confirm',
                                submit = layero.find('iframe').contents().find('#' + submitID);
                            iframeWindow.layui.form.on('submit(' + submitID + ')', function (data) {
                                const field = data.field;
                                console.log(field);
                                admin.post('add', field, function () {
                                    table.reload('grid');
                                    layer.close(index);
                                }, function (result) {
                                    admin.error(admin.OPT_FAILURE, result.error);
                                    layer.close(index);
                                });
                            });
                            submit.trigger('click');
                        }
                    });
                    break;
            }
            ;
        });

        table.on('tool(grid)', function (obj) {
            const data = obj.data;
            if (obj.event === 'del') {
                layer.confirm(admin.DEL_QUESTION, function (index) {
                    admin.post("del", {ids: data.id}, function () {
                        table.reload('grid');
                        layer.close(index);
                    });
                });
            } else if (obj.event = 'edit') {
                layer.open({
                    type: 2,
                    title: admin.EDIT,
                    content: 'toedit.html?id=' + data.id,
                    area: ['400px', '610px'],
                    btn: admin.BUTTONS,
                    resize: false,
                    yes: function (index, layero) {
                        const iframeWindow = window['layui-layer-iframe' + index], submitID = 'btn_confirm',
                            submit = layero.find('iframe').contents().find('#' + submitID);
                        iframeWindow.layui.form.on('submit(' + submitID + ')', function (data) {
                            const field = data.field;
                            admin.post('edit', admin.toJson(field), function () {
                                table.reload('grid');
                                layer.close(index);
                            }, function (result) {
                                admin.error(admin.OPT_FAILURE, result.error);
                                layer.close(index);
                            });
                        });
                        submit.trigger('click');
                    }
                });
            }
        });
    });
</script>
</body>
</html>
