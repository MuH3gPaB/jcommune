/**
 * Copyright (C) 2011  JTalks.org Team
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.jtalks.jcommune.web.controller.plugins;

import com.google.common.collect.ImmutableMap;
import org.jtalks.common.model.entity.User;
import org.jtalks.jcommune.model.entity.JCUser;
import org.jtalks.jcommune.model.entity.Language;
import org.jtalks.jcommune.model.plugins.Plugin;
import org.jtalks.jcommune.model.plugins.SimpleAuthenticationPlugin;
import org.jtalks.jcommune.model.plugins.exceptions.NoConnectionException;
import org.jtalks.jcommune.model.plugins.exceptions.UnexpectedErrorException;
import org.jtalks.jcommune.service.UserService;
import org.jtalks.jcommune.service.nontransactional.EncryptionService;
import org.jtalks.jcommune.service.plugins.PluginFilter;
import org.jtalks.jcommune.service.plugins.PluginLoader;
import org.jtalks.jcommune.service.plugins.TypeFilter;
import org.jtalks.jcommune.web.dto.RegisterUserDto;
import org.jtalks.jcommune.web.dto.json.JsonResponse;
import org.jtalks.jcommune.web.dto.json.JsonResponseStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import java.util.*;

@Controller
public class PoulpeAuthController {

    public static final String REGISTRATION = "registration";
    public static final String AFTER_REGISTRATION = "afterRegistration";
    public static final boolean DEFAULT_AUTOSUBSCRIBE = true;

    private EncryptionService encryptionService;
    private UserService userService;

    private PluginLoader pluginLoader;

    @Autowired
    public PoulpeAuthController(PluginLoader pluginLoader,
                                EncryptionService encryptionService,
                                UserService userService) {
        this.pluginLoader = pluginLoader;
        this.encryptionService = encryptionService;
        this.userService = userService;
    }

    /**
     * Register {@link org.jtalks.jcommune.model.entity.JCUser} from populated in form {@link RegisterUserDto}.
     * <p/>
     *
     * @param userDto {@link RegisterUserDto} populated in form
     * @param result  result of {@link RegisterUserDto} validation
     * @param locale  to set currently selected language as user's default
     * @return redirect to / if registration successful or back to "/registration" if failed
     */
    @RequestMapping(value = "/user/new/poulpe", method = RequestMethod.POST)
    public ModelAndView registerUser(@ModelAttribute("newUser") RegisterUserDto userDto,
                                     BindingResult result, Locale locale) {

        register(userDto, result, locale);
        if (result.hasErrors()) {
            return new ModelAndView(REGISTRATION);
        }
        storeUser(userDto, locale);
        return new ModelAndView(AFTER_REGISTRATION);
    }

    /**
     * Register {@link org.jtalks.jcommune.model.entity.JCUser} from populated {@link RegisterUserDto}.
     * <p/>
     *
     * @param userDto {@link RegisterUserDto} populated in form
     * @param result  result of {@link RegisterUserDto} validation
     * @param locale  to set currently selected language as user's default
     * @return redirect validation result in JSON format
     */
    @RequestMapping(value = "/user/new_ajax/poulpe", method = RequestMethod.POST)
    @ResponseBody
    public JsonResponse registerUserAjax(@ModelAttribute("newUser") RegisterUserDto userDto,
                                         BindingResult result, Locale locale) {
        register(userDto, result, locale);
        if (result.hasErrors()) {
            return new JsonResponse(JsonResponseStatus.FAIL, result.getAllErrors());
        }
        storeUser(userDto, locale);
        return new JsonResponse(JsonResponseStatus.SUCCESS);
    }

    private void register(RegisterUserDto userDto, BindingResult result, Locale locale) {
        SimpleAuthenticationPlugin authPlugin = getAuthPlugin();
        if (authPlugin != null && authPlugin.getState() == Plugin.State.ENABLED) {
            String passwordHash = userDto.getPassword().isEmpty() ? ""
                    : encryptionService.encryptPassword(userDto.getPassword());
            List<Map<String, String>> errors = new ArrayList<>();
            try {
                errors.addAll(authPlugin.registerUser(userDto.getUsername(), passwordHash, userDto.getEmail()));
            } catch (NoConnectionException e) {
                errors.add(new ImmutableMap.Builder<String, String>()
                        .put("", "Registration service is not available").build());
            } catch (UnexpectedErrorException e) {
                errors.add(new ImmutableMap.Builder<String, String>()
                        .put("", "Unexpected error was happened").build());
            }
            parseValidationErrors(errors, result, locale);
        }
    }

    private SimpleAuthenticationPlugin getAuthPlugin() {
        Class cl = SimpleAuthenticationPlugin.class;
        PluginFilter pluginFilter = new TypeFilter(cl);
        List<Plugin> plugins = pluginLoader.getPlugins(pluginFilter);
        return !plugins.isEmpty() ? (SimpleAuthenticationPlugin) plugins.get(0) : null;
    }

    private void parseValidationErrors(List<Map<String, String>> errors, BindingResult result, Locale locale) {
        for (Map<String, String> errorEntries : errors) {
            Map.Entry errorEntry = errorEntries.entrySet().iterator().next();
            ResourceBundle resourceBundle = ResourceBundle.getBundle("ValidationMessages", locale);
            if (!errorEntry.getKey().toString().isEmpty()) {
                Map.Entry<String, String> error = parseErrorCode(errorEntry.getKey().toString(), resourceBundle);
                if (error != null) {
                    result.rejectValue(error.getKey(), null, error.getValue());
                }
            } else {
                result.rejectValue("captcha", null, errorEntry.getValue().toString());
            }
        }
    }

    private Map.Entry<String, String> parseErrorCode(String errorCode, ResourceBundle resourceBundle) {
        Map.Entry<String, String> error = null;
        if (resourceBundle.containsKey(errorCode)) {
            String errorMessage = resourceBundle.getString(errorCode);
            if (errorCode.contains("email")) {
                errorMessage = errorMessage.replace("{max}", String.valueOf(User.EMAIL_MAX_LENGTH));
                error = new HashMap.SimpleEntry<>("email", errorMessage);
            } else if (errorCode.contains("username")) {
                errorMessage = errorMessage.replace("{min}", String.valueOf(User.USERNAME_MIN_LENGTH))
                        .replace("{max}", String.valueOf(User.USERNAME_MAX_LENGTH));
                error = new HashMap.SimpleEntry<>("username", errorMessage);
            } else if (errorCode.contains("password")) {
                errorMessage = errorMessage.replace("{min}", String.valueOf(User.PASSWORD_MIN_LENGTH))
                        .replace("{max}", String.valueOf(User.PASSWORD_MAX_LENGTH));
                error = new HashMap.SimpleEntry<>("password", errorMessage);
            }
        }
        return error;
    }

    /**
     * Just registers a new user without any additional checks, it gets rid of duplication in enclosing
     * {@code registerUser()} methods.
     *
     * @param userDto coming from enclosing methods, this object is built by Spring MVC
     * @param locale  the locale of user she can pass in GET requests
     */
    private void storeUser(RegisterUserDto userDto, Locale locale) {
        JCUser user = userDto.createUser();
        user.setLanguage(Language.byLocale(locale));
        user.setAutosubscribe(DEFAULT_AUTOSUBSCRIBE);
        userService.registerUser(user);
    }
}
